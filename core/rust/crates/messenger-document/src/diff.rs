//! Document Diff events for incremental UI invalidation.
//! (TARGET.md §4.1, §6)

use serde::{Deserialize, Serialize};

use crate::model::{Block, BlockId};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "diff", rename_all = "snake_case")]
pub enum DocumentDiff {
    /// Append a newly created block (e.g. new paragraph or code fence opened).
    Append { block: Block },
    /// Update an existing streaming block in-place (fine-grained invalidation).
    Update { block: Block },
    /// Finalize a block: marks it immutable. Downstream renderers can freeze layout & cache.
    Finalize { id: BlockId },
    /// Clear the entire document (e.g. regenerate message).
    Reset,
}

/// A batch of diffs collected during a streaming window (20–50 ms).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct DiffBatch {
    pub diffs: Vec<DocumentDiff>,
}

impl DiffBatch {
    pub fn new() -> Self {
        Self { diffs: Vec::new() }
    }

    pub fn is_empty(&self) -> bool {
        self.diffs.is_empty()
    }

    pub fn push(&mut self, diff: DocumentDiff) {
        self.diffs.push(diff);
    }
}
