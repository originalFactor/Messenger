//! Persistence for the wasm32 (browser) build.
//!
//! SQLite itself is compiled to wasm by `sqlite-wasm-rs` (reached through
//! rusqlite's `ffi-sqlite-wasm-rs` feature), and that build is *in-process* —
//! there is no separate WASM module to run in a dedicated worker, which rules
//! out every worker-only OPFS VFS crate.
//!
//! So the database lives in SQLite's own in-memory image and this module
//! mirrors that image into the browser's origin-private file system:
//!
//! - [`prepare`] reads an existing image into [`RESTORE_IMAGE`] before the
//!   store opens (one async step, at startup).
//! - [`Store::snapshot`] (see `store.rs`) serializes the live database after
//!   each successful mutation and hands the bytes to [`stage_snapshot`].
//! - [`stage_snapshot`] writes them through a single coalescing background
//!   writer: at most one OPFS write is in flight, and when several mutations
//!   land during a write only the newest image is written next. That keeps
//!   the synchronous `rusqlite::Connection` API intact without an async
//!   mutex, and it never writes an image older than one that already landed.
//!
//! `FileSystemSyncAccessHandle` is deliberately not used: it is only exposed
//! in workers, so it cannot back a store running on the page.

use std::cell::{Cell, RefCell};

use js_sys::Uint8Array;
use wasm_bindgen::JsCast;
use wasm_bindgen_futures::{JsFuture, spawn_local};
use web_sys::{
    FileSystemDirectoryHandle, FileSystemGetDirectoryOptions, FileSystemGetFileOptions,
    FileSystemWritableFileStream, StorageManager, WritableStream,
};

thread_local! {
    /// Database image read from OPFS at startup; taken by `Store::open`.
    static RESTORE_IMAGE: RefCell<Option<Vec<u8>>> = const { RefCell::new(None) };
    /// The OPFS file this store mirrors into.
    static FILE_HANDLE: RefCell<Option<web_sys::FileSystemFileHandle>> = const { RefCell::new(None) };
    /// Newest image waiting to be written (replaced, never queued).
    static PENDING_IMAGE: RefCell<Option<Vec<u8>>> = const { RefCell::new(None) };
    static WRITER_RUNNING: Cell<bool> = const { Cell::new(false) };
}

/// Subdirectory inside the origin-private file system.
const OPFS_DIR: &str = "messenger";

fn window() -> Result<web_sys::Window, String> {
    web_sys::window().ok_or_else(|| "no window".to_string())
}

async fn opfs_root() -> Result<FileSystemDirectoryHandle, String> {
    let storage: StorageManager = window()?.navigator().storage();
    let root: js_sys::Object = JsFuture::from(storage.get_directory())
        .await
        .map_err(|e| format!("OPFS unavailable: {e:?}"))?
        .dyn_into()
        .map_err(|_| "OPFS root is not a directory handle".to_string())?;
    let dir = FileSystemGetDirectoryOptions::new();
    dir.set_create(true);
    JsFuture::from(
        root.unchecked_ref::<FileSystemDirectoryHandle>()
            .get_directory_handle_with_options(OPFS_DIR, &dir),
    )
    .await
    .map_err(|e| format!("OPFS directory {OPFS_DIR}: {e:?}"))?
    .dyn_into()
    .map_err(|_| "OPFS subdirectory is not a directory handle".to_string())
}

/// Opens/creates `name` in OPFS and loads its current bytes into
/// [`RESTORE_IMAGE`] so the store can deserialize them when it opens.
///
/// Must be awaited before `Store::open` on wasm.
pub async fn prepare(name: &str) -> Result<(), String> {
    let dir = opfs_root().await?;
    let options = FileSystemGetFileOptions::new();
    options.set_create(true);
    let handle: web_sys::FileSystemFileHandle = JsFuture::from(
        dir.get_file_handle_with_options(name, &options),
    )
    .await
    .map_err(|e| format!("OPFS file {name}: {e:?}"))?
    .dyn_into()
    .map_err(|_| "OPFS entry is not a file handle".to_string())?;

    let file = JsFuture::from(handle.get_file())
        .await
        .map_err(|e| format!("OPFS read {name}: {e:?}"))?
        .dyn_into::<web_sys::File>()
        .map_err(|_| "OPFS read did not yield a File".to_string())?;

    let image = if file.size() > 0.0 {
        let buffer = JsFuture::from(file.array_buffer())
            .await
            .map_err(|e| format!("OPFS read {name}: {e:?}"))?;
        Uint8Array::new(&buffer).to_vec()
    } else {
        Vec::new()
    };

    FILE_HANDLE.with(|slot| *slot.borrow_mut() = Some(handle.clone()));
    RESTORE_IMAGE.with(|slot| *slot.borrow_mut() = Some(image));
    Ok(())
}

/// Takes the image read by [`prepare`] (empty when the file did not exist).
pub(crate) fn take_restore_image() -> Vec<u8> {
    RESTORE_IMAGE.with(|slot| slot.borrow_mut().take()).unwrap_or_default()
}

/// Stages a serialized database image for writing and starts the writer when
/// it is idle. Returns immediately; the write happens on the current task.
pub(crate) fn stage_snapshot(serialized: Vec<u8>) {
    PENDING_IMAGE.with(|slot| *slot.borrow_mut() = Some(serialized));
    start_writer();
}

fn start_writer() {
    if WRITER_RUNNING.with(|flag| flag.get()) {
        return;
    }
    WRITER_RUNNING.with(|flag| flag.set(true));
    spawn_local(async {
        // Coalescing loop: write whatever is newest until nothing is pending.
        while let Some(bytes) = PENDING_IMAGE.with(|slot| slot.borrow_mut().take()) {
            if let Err(error) = write_image(&bytes).await {
                // Persistence must never abort the app; the in-memory
                // database stays authoritative for this session.
                crate::store::log_snapshot_failure(&error);
            }
        }
        WRITER_RUNNING.with(|flag| flag.set(false));
        // An image staged between the last `take()` and the flag reset.
        if PENDING_IMAGE.with(|slot| slot.borrow().is_some()) {
            start_writer();
        }
    });
}

async fn write_image(bytes: &[u8]) -> Result<(), String> {
    let handle = FILE_HANDLE
        .with(|slot| slot.borrow().clone())
        .ok_or_else(|| "snapshot written before prepare()".to_string())?;
    let stream: FileSystemWritableFileStream = JsFuture::from(handle.create_writable())
        .await
        .map_err(|e| format!("OPFS open for write: {e:?}"))?
        .dyn_into()
        .map_err(|_| "OPFS entry cannot create a writable stream".to_string())?;

    JsFuture::from(stream.seek_with_f64(0.0).map_err(|e| format!("OPFS seek: {e:?}"))?)
        .await
        .map_err(|e| format!("OPFS seek: {e:?}"))?;
    JsFuture::from(
        stream
            .write_with_u8_array(bytes)
            .map_err(|e| format!("OPFS write: {e:?}"))?,
    )
    .await
    .map_err(|e| format!("OPFS write: {e:?}"))?;
    // Truncate after writing: a shrunk database must not leave tail bytes.
    JsFuture::from(
        stream
            .truncate_with_f64(bytes.len() as f64)
            .map_err(|e| format!("OPFS truncate: {e:?}"))?,
    )
    .await
    .map_err(|e| format!("OPFS truncate: {e:?}"))?;

    let writable: WritableStream = stream
        .dyn_into()
        .map_err(|_| "OPFS stream is not a WritableStream".to_string())?;
    JsFuture::from(writable.close())
        .await
        .map_err(|e| format!("OPFS close: {e:?}"))?;
    Ok(())
}
