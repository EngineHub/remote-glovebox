//#![deny(warnings)]

use actix_web::{App, get, HttpResponse, HttpServer, Result, web};
use anyhow::Context;
use http::Uri;
use log::LevelFilter;
use serde::Deserialize;
use simplelog::{Config, TerminalMode};
use structopt::StructOpt;

mod jar_manager;

#[derive(StructOpt)]
#[structopt(name = "glovebox", about = "A Javadoc Server")]
struct Glovebox {
    #[structopt(long, help = "The URI to contact for JARs")]
    maven: Uri,
}

#[actix_web::main]
async fn main() -> anyhow::Result<()> {
    simplelog::TermLogger::init(
        LevelFilter::Debug, Config::default(), TerminalMode::Stderr,
    )?;

    HttpServer::new(|| {
        App::new().service(
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
async fn javadoc(info: web::Path<JavadocInfo>) -> Result<String> {
    Ok(format!("haha javadoc from {}:{}:{}/{} go 404", info.group, info.name, info.version, info.path))
}
