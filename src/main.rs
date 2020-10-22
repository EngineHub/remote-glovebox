#![deny(warnings)]

use actix::{Actor, Addr};
use actix_web::{get, web, App, HttpResponse, HttpServer, Result};
use anyhow::Context;
use http::Uri;
use log::LevelFilter;
use serde::Deserialize;
use simplelog::{Config, TerminalMode};
use structopt::StructOpt;

use crate::jar_manager::{JarDataRequest, JarManager};

mod jar_manager;

#[derive(StructOpt)]
#[structopt(name = "glovebox", about = "A Javadoc Server")]
struct Glovebox {
    #[structopt(long, help = "The URI to contact for JARs")]
    maven: Uri,
    #[structopt(
        long,
        help = "The amount of memory for the JAR cache, approximately",
        default_value = "100000000"
    )]
    jar_cache_size: usize,
}

struct GloveboxData {
    jar_manager: Addr<JarManager>,
}

#[actix_web::main]
async fn main() -> anyhow::Result<()> {
    simplelog::TermLogger::init(LevelFilter::Debug, Config::default(), TerminalMode::Stderr)?;

    HttpServer::new(|| {
        let args: Glovebox = Glovebox::from_args();
        App::new()
            .data(GloveboxData {
                jar_manager: JarManager::new(args.maven, args.jar_cache_size).start(),
            })
            .service(
                // prefixes all resources and routes attached to it...
                web::scope("/javadoc")
                    .service(nearly_javadoc)
                    .service(javadoc),
            )
    })
    .bind("127.0.0.1:8080")?
    .run()
    .await
    .context("HTTP Server Error")
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
            path: fixed_path,
        })
        .await
        .map_err(actix_web::error::ErrorInternalServerError)??;
    Ok(HttpResponse::Ok().body(bytes))
}
