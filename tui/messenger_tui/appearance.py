"""Shared terminal palettes; keep contrast and focus consistent across dialogs."""

from textual.theme import Theme

THEMES = (
    Theme(
        name="messenger-dark",
        primary="#8CB9C4",
        accent="#8CB9C4",
        background="#19212B",
        surface="#232F3D",
        panel="#2C3B4C",
        foreground="#E1E8EF",
        error="#F0A09B",
        dark=True,
    ),
    Theme(
        name="messenger-light",
        primary="#285E70",
        accent="#285E70",
        background="#EDF2F6",
        surface="#FFFFFF",
        panel="#D9E3EC",
        foreground="#253747",
        error="#A12635",
        dark=False,
    ),
)
