//! In-memory Document representation holding the blocks and tracking changes.

use crate::diff::{DiffBatch, DocumentDiff};
use crate::model::{Block, BlockId};

#[derive(Debug, Clone, Default)]
pub struct Document {
    blocks: Vec<Block>,
    next_id: BlockId,
}

impl Document {
    pub fn new() -> Self {
        Self {
            blocks: Vec::new(),
            next_id: 1,
        }
    }

    pub fn blocks(&self) -> &[Block] {
        &self.blocks
    }

    pub fn next_id(&mut self) -> BlockId {
        let id = self.next_id;
        self.next_id += 1;
        id
    }

    pub fn get_block(&self, id: BlockId) -> Option<&Block> {
        self.blocks.iter().find(|b| b.id() == id)
    }

    pub fn get_block_mut(&mut self, id: BlockId) -> Option<&mut Block> {
        self.blocks.iter_mut().find(|b| b.id() == id)
    }

    pub fn last_block(&self) -> Option<&Block> {
        self.blocks.last()
    }

    pub fn last_block_mut(&mut self) -> Option<&mut Block> {
        self.blocks.last_mut()
    }

    pub fn append(&mut self, block: Block) -> DocumentDiff {
        self.blocks.push(block.clone());
        DocumentDiff::Append { block }
    }

    pub fn update(&mut self, block: Block) -> Option<DocumentDiff> {
        let id = block.id();
        if let Some(pos) = self.blocks.iter().position(|b| b.id() == id) {
            self.blocks[pos] = block.clone();
            Some(DocumentDiff::Update { block })
        } else {
            None
        }
    }

    pub fn finalize(&mut self, id: BlockId) -> Option<DocumentDiff> {
        if let Some(block) = self.get_block_mut(id) {
            block.finalize();
            Some(DocumentDiff::Finalize { id })
        } else {
            None
        }
    }

    pub fn reset(&mut self) -> DocumentDiff {
        self.blocks.clear();
        self.next_id = 1;
        DocumentDiff::Reset
    }

    /// Apply a diff to this document (mirroring receiver side on a platform client).
    pub fn apply(&mut self, diff: &DocumentDiff) {
        match diff {
            DocumentDiff::Append { block } => {
                self.blocks.push(block.clone());
                if block.id() >= self.next_id {
                    self.next_id = block.id() + 1;
                }
            }
            DocumentDiff::Update { block } => {
                if let Some(pos) = self.blocks.iter().position(|b| b.id() == block.id()) {
                    self.blocks[pos] = block.clone();
                }
            }
            DocumentDiff::Finalize { id } => {
                if let Some(block) = self.get_block_mut(*id) {
                    block.finalize();
                }
            }
            DocumentDiff::Reset => {
                self.blocks.clear();
                self.next_id = 1;
            }
        }
    }

    /// Apply an entire batch of diffs.
    pub fn apply_batch(&mut self, batch: &DiffBatch) {
        for diff in &batch.diffs {
            self.apply(diff);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{BlockStatus, Inline};

    #[test]
    fn document_lifecycle_and_diffs() {
        let mut doc = Document::new();
        let id = doc.next_id();
        let p = Block::Paragraph {
            id,
            inlines: vec![Inline::Text {
                text: "Hello".into(),
            }],
            status: BlockStatus::Streaming,
        };

        let diff1 = doc.append(p);
        assert!(matches!(diff1, DocumentDiff::Append { .. }));
        assert_eq!(doc.blocks().len(), 1);

        let updated_p = Block::Paragraph {
            id,
            inlines: vec![Inline::Text {
                text: "Hello World".into(),
            }],
            status: BlockStatus::Streaming,
        };
        let diff2 = doc.update(updated_p).unwrap();
        assert!(matches!(diff2, DocumentDiff::Update { .. }));

        let diff3 = doc.finalize(id).unwrap();
        assert!(matches!(diff3, DocumentDiff::Finalize { id: 1 }));
        assert!(doc.blocks()[0].is_finalized());

        // Mirror doc
        let mut mirror = Document::new();
        mirror.apply(&diff1);
        mirror.apply(&diff2);
        mirror.apply(&diff3);
        assert_eq!(mirror.blocks(), doc.blocks());
    }
}
