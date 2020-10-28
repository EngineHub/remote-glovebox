#![deny(warnings)]
#![feature(future_readiness_fns)]

use actix::{Actor, Addr};
use actix_web::body::{Body, ResponseBody};
use actix_web::dev::Service;
use actix_web::http::HeaderValue;
use actix_web::middleware::errhandlers::{ErrorHandlerResponse, ErrorHandlers};
use actix_web::web::Bytes;
use actix_web::{get, web, App, HttpResponse, HttpServer, Result};
use anyhow::Context;
use http::Uri;
use log::LevelFilter;
use serde::Deserialize;
use simplelog::{Config, TerminalMode};
use structopt::StructOpt;

use crate::jar_manager::{JarDataRequest, JarManager};

mod jar_manager;
mod maven;

#[derive(StructOpt)]
#[structopt(name = "glovebox", about = "A Javadoc Server")]
struct Glovebox {
    #[structopt(short, long, help = "The host to bind to", default_value = "localhost")]
    host: String,
    #[structopt(short, long, help = "The port to bind to", default_value = "8080")]
    port: u32,
    #[structopt(long, help = "The URI to contact for JARs")]
    maven: Uri,
    #[structopt(
        long,
        help = "The amount of memory for the JAR cache, approximately",
        default_value = "100MiB"
    )]
    jar_mem_cache_size: byte_unit::Byte,
    #[structopt(
        long,
        help = "The amount of disk space for the JAR cache, approximately",
        default_value = "1GiB"
    )]
    jar_fs_cache_size: byte_unit::Byte,
}

struct GloveboxData {
    jar_manager: Addr<JarManager>,
}

#[actix_web::main]
async fn main() -> anyhow::Result<()> {
    simplelog::TermLogger::init(LevelFilter::Debug, Config::default(), TerminalMode::Stderr)?;

    let args: Glovebox = Glovebox::from_args();
    let jar_manager =
        JarManager::new(args.maven, args.jar_mem_cache_size, args.jar_fs_cache_size)?.start();

    HttpServer::new(move || {
        let four_zero_four_page = std::fs::read_to_string("./404.html")
            .ok()
            .map(Bytes::from)
            .unwrap_or_else(|| Bytes::from("Not found, try /javadoc/{group}/{name}/{version}"));
        App::new()
            .data(GloveboxData {
                jar_manager: jar_manager.clone(),
            })
            .wrap(
                ErrorHandlers::new().handler(http::StatusCode::NOT_FOUND, move |res| {
                    render_404(four_zero_four_page.clone(), res)
                }),
            )
            .wrap_fn::<Body, _, _>(|mut req, srv| {
                if req.method() == http::Method::HEAD {
                    // treat head as a get
                    req.head_mut().method = http::Method::GET;
                }
                srv.call(req)
            })
            .service(
                // prefixes all resources and routes attached to it...
                web::scope("/javadoc")
                    .service(nearly_javadoc)
                    .service(javadoc),
            )
    })
    .bind(format!("{}:{}", args.host, args.port))?
    .run()
    .await
    .context("HTTP Server Error")
}

fn render_404(
    page: Bytes,
    mut res: actix_web::dev::ServiceResponse<Body>,
) -> Result<ErrorHandlerResponse<Body>> {
    res.headers_mut().insert(
        http::header::CONTENT_TYPE,
        HeaderValue::from_str(mime::TEXT_HTML.as_ref())?,
    );
    Ok(ErrorHandlerResponse::Response(
        res.map_body(|_, _| ResponseBody::Other(page.into())),
    ))
}

#[derive(Deserialize)]
struct PartialJavadocInfo {
    group: String,
    name: String,
    version: String,
}

#[get("/{group}/{name}/{version}")]
async fn nearly_javadoc(info: web::Path<PartialJavadocInfo>) -> Result<HttpResponse> {
    Ok(HttpResponse::PermanentRedirect()
        .header(
            http::header::LOCATION,
            format!("/javadoc/{}/{}/{}/", info.group, info.name, info.version),
        )
        .finish())
}

#[derive(Deserialize)]
struct JavadocInfo {
    group: String,
    name: String,
    version: String,
    path: String,
}

#[get("/{group}/{name}/{version}/{path:.*}")]
async fn javadoc(
    data: web::Data<GloveboxData>,
    info: web::Path<JavadocInfo>,
) -> Result<HttpResponse> {
    let fixed_path = match info.path.as_str() {
        "" => String::from("index.html"),
        _ => String::from(&info.path),
    };
    let bytes = data
        .jar_manager
        .send(JarDataRequest {
            group: String::from(&info.group),
            name: String::from(&info.name),
            version: String::from(&info.version),
            path: String::from(&fixed_path),
        })
        .await
        .map_err(actix_web::error::ErrorInternalServerError)?
        .map_err(|e| match e {
            jar_manager::Error::NotFound => actix_web::error::ErrorNotFound(e),
            _ => actix_web::error::ErrorInternalServerError(e),
        })?;
    let mut response = HttpResponse::Ok();
    if let Some(index) = fixed_path.find('.') {
        response.content_type(
            match &fixed_path[index + 1..] {
                "html" => mime::TEXT_HTML,
                "js" => mime::APPLICATION_JAVASCRIPT,
                "css" => mime::TEXT_CSS,
                _ => mime::APPLICATION_OCTET_STREAM,
            }
            .as_ref(),
        );
    }
    Ok(response.body(bytes))
}
