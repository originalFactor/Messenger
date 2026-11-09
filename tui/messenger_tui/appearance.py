"""Shared terminal palettes; keep contrast and focus consistent across dialogs."""

from textual.theme import Theme

THEMES = (
    Theme(
        name="messenger-dark",
        primary="#D4D4D4",
        accent="#D4D4D4",
        background="#181818",
        surface="#222222",
        panel="#303030",
        foreground="#ECECEC",
        error="#F28B82",
        dark=True,
    ),
    Theme(
        name="messenger-light",
        primary="#505050",
        accent="#505050",
        background="#FAFAFA",
        surface="#F0F0F0",
        panel="#DEDEDE",
        foreground="#252525",
        error="#A12635",
        dark=False,
    ),
)
