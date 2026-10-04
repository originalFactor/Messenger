//! Document AST model with stable Block IDs and immutable/streaming states.
//! (TARGET.md §4.1)

use serde::{Deserialize, Serialize};

pub type BlockId = u64;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BlockStatus {
    Streaming,
    Finalized,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Inline {
    Text { text: String },
    Bold { text: String },
    Italic { text: String },
    Code { code: String },
    Math { formula: String },
    Link { text: String, url: String },
    Strikethrough { text: String },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Block {
    Paragraph {
        id: BlockId,
        inlines: Vec<Inline>,
        status: BlockStatus,
    },
    Heading {
        id: BlockId,
        level: u8,
        text: String,
        status: BlockStatus,
    },
    CodeBlock {
        id: BlockId,
        language: Option<String>,
        code: String,
        status: BlockStatus,
    },
    Math {
        id: BlockId,
        formula: String,
        status: BlockStatus,
    },
    List {
        id: BlockId,
        ordered: bool,
        items: Vec<Vec<Inline>>,
        status: BlockStatus,
    },
    Quote {
        id: BlockId,
        text: String,
        status: BlockStatus,
    },
    ToolCall {
        id: BlockId,
        call_id: String,
        name: String,
        arguments: String,
        output: Option<String>,
        is_error: bool,
        status: BlockStatus,
    },
    Think {
        id: BlockId,
        content: String,
        status: BlockStatus,
    },
    Divider {
        id: BlockId,
    },
}

impl Block {
    pub fn id(&self) -> BlockId {
        match self {
            Block::Paragraph { id, .. }
            | Block::Heading { id, .. }
            | Block::CodeBlock { id, .. }
            | Block::Math { id, .. }
            | Block::List { id, .. }
            | Block::Quote { id, .. }
            | Block::ToolCall { id, .. }
            | Block::Think { id, .. }
            | Block::Divider { id } => *id,
        }
    }

    pub fn status(&self) -> BlockStatus {
        match self {
            Block::Paragraph { status, .. }
            | Block::Heading { status, .. }
            | Block::CodeBlock { status, .. }
            | Block::Math { status, .. }
            | Block::List { status, .. }
            | Block::Quote { status, .. }
            | Block::ToolCall { status, .. }
            | Block::Think { status, .. } => *status,
            Block::Divider { .. } => BlockStatus::Finalized,
        }
    }

    pub fn is_finalized(&self) -> bool {
        self.status() == BlockStatus::Finalized
    }

    pub fn finalize(&mut self) {
        match self {
            Block::Paragraph { status, .. }
            | Block::Heading { status, .. }
            | Block::CodeBlock { status, .. }
            | Block::Math { status, .. }
            | Block::List { status, .. }
            | Block::Quote { status, .. }
            | Block::ToolCall { status, .. }
            | Block::Think { status, .. } => *status = BlockStatus::Finalized,
            Block::Divider { .. } => {}
        }
    }
}
