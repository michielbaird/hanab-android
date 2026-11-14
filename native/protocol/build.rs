//! Hands the generator which version of the website's protocol crate it is built against (from Cargo.lock), and the commit
//! that version was made from, which it writes into the generated file.
//!
//! A git dependency names its commit in Cargo.lock. A crates.io version has it in the `.cargo_vcs_info.json` that
//! `cargo publish` puts in the package, in Cargo's registry sources.
use std::{env, fs, path::PathBuf};

fn field(block: &str, name: &str) -> Option<String> {
    block
        .lines()
        .find_map(|line| line.strip_prefix(&format!("{name} = \"")))
        .and_then(|rest| rest.strip_suffix('"'))
        .map(str::to_owned)
}

fn cargo_home() -> Option<PathBuf> {
    env::var_os("CARGO_HOME")
        .map(PathBuf::from)
        .or_else(|| env::var_os("USERPROFILE").or_else(|| env::var_os("HOME")).map(|home| PathBuf::from(home).join(".cargo")))
}

/// The commit a published crate version was made from.
fn published_commit(version: &str) -> Option<String> {
    let sources = cargo_home()?.join("registry").join("src");
    for index in fs::read_dir(sources).ok()? {
        let info = index.ok()?.path().join(format!("hanabi_protocol-{version}")).join(".cargo_vcs_info.json");
        if let Ok(text) = fs::read_to_string(info) {
            return text.split("\"sha1\"").nth(1)?.split('"').nth(1).map(str::to_owned);
        }
    }
    None
}

/// Where Cargo keeps the pinned crate's sources: the registry's copy of a published version, or the checkout of a git commit.
fn crate_directory(version: &str, rev: &str, git: bool) -> Option<PathBuf> {
    let home = cargo_home()?;
    // A git pin keeps the version of the crate it was made from, so the registry may hold a different, older copy of the same version.
    for index in fs::read_dir(home.join("registry").join("src")).into_iter().flatten().flatten().filter(|_| !git) {
        let dir = index.path().join(format!("hanabi_protocol-{version}"));
        if dir.join("fixtures").is_dir() {
            return Some(dir);
        }
    }
    for repo in fs::read_dir(home.join("git").join("checkouts")).into_iter().flatten().flatten() {
        let dir = repo.path().join(rev.get(..7)?).join("server").join("protocol");
        if dir.join("fixtures").is_dir() {
            return Some(dir);
        }
    }
    None
}

fn main() {
    println!("cargo:rerun-if-changed=Cargo.lock");
    println!("cargo:rerun-if-changed=Cargo.toml");
    let lock = fs::read_to_string("Cargo.lock").expect("Cargo.lock");
    let block = lock
        .split("[[package]]")
        .find(|block| field(block, "name").as_deref() == Some("hanabi_protocol"))
        .expect("Cargo.lock has hanabi_protocol");
    let version = field(block, "version").expect("a version");
    let git = field(block, "source").is_some_and(|source| source.starts_with("git+"));
    let rev = match field(block, "source") {
        Some(source) if git => source.rsplit('#').next().map(str::to_owned),
        _ => published_commit(&version),
    }
    .unwrap_or_else(|| "unknown".into());
    let dir = crate_directory(&version, &rev, git).map(|dir| dir.display().to_string()).unwrap_or_default();
    println!("cargo:rustc-env=HANABI_PROTOCOL_DIR={dir}");
    println!("cargo:rustc-env=HANABI_PROTOCOL_VERSION={version}");
    println!("cargo:rustc-env=HANABI_PROTOCOL_REV={rev}");
}
