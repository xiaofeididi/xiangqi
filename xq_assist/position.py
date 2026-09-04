"""Xiangqi board state: FEN parsing/generation, coordinate helpers, move differ."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple


FILE_NAMES = "abcdefghi"
RANK_NUMBERS = "0123456789"


@dataclass
class Position:
    """Board indexed as `cells[row][col]` with row 0 = rank 0 (black back rank).

    Piece codes: side (w/b) + type (r n b a k c p), e.g. 'wr' = white/red rook.
    Internally uses Xiangqi-standard orientation: black at top, red at bottom.
    """

    cells: List[List[Optional[str]]] = field(default_factory=list)
    side_to_move: str = "w"
    move_num: int = 1
    halfmove_clock: int = 0

    @classmethod
    def from_startpos(cls) -> "Position":
        return cls.from_fen(START_FEN)

    @classmethod
    def from_fen(cls, fen: str) -> "Position":
        parts = fen.strip().split()
        if not parts:
            raise ValueError("empty FEN")
        board_part = parts[0]
        rows = board_part.split("/")
        if len(rows) != 10:
            raise ValueError(f"FEN must have 10 ranks, got {len(rows)}")
        cells: List[List[Optional[str]]] = []
        for rank_str in rows:
            row: List[Optional[str]] = []
            for ch in rank_str:
                if ch.isdigit():
                    row.extend([None] * int(ch))
                elif ch in "RNBAKCP":
                    row.append("w" + ch.lower())
                elif ch in "rnbakcp":
                    row.append("b" + ch)
                else:
                    raise ValueError(f"bad FEN piece token: {ch!r}")
            if len(row) != 9:
                raise ValueError(f"FEN rank must have 9 files, got {len(row)}")
            cells.append(row)
        side = parts[1] if len(parts) > 1 and parts[1] in "wb" else "w"
        halfmove = int(parts[4]) if len(parts) > 4 and parts[4].isdigit() else 0
        move_num = int(parts[5]) if len(parts) > 5 and parts[5].isdigit() else 1
        return cls(cells=cells, side_to_move=side, move_num=move_num, halfmove_clock=halfmove)

    def to_fen(self) -> str:
        rows: List[str] = []
        for row in self.cells:
            buf: List[str] = []
            empty = 0
            for cell in row:
                if cell is None:
                    empty += 1
                    continue
                code = cell
                if empty:
                    buf.append(str(empty))
                    empty = 0
                if len(code) == 2 and code[0] in "wb" and code[1] in "rnbakcp":
                    buf.append(code[1].upper() if code[0] == "w" else code[1])
            if empty:
                buf.append(str(empty))
            rows.append("".join(buf))
        return f"{'/'.join(rows)} {self.side_to_move} - - {self.halfmove_clock} {self.move_num}"
    def piece_at(self, rank: int, file: int) -> Optional[str]:
        if not (0 <= rank <= 9 and 0 <= file <= 8):
            return None
        return self.cells[rank][file]

    def set_piece(self, rank: int, file: int, piece: Optional[str]) -> None:
        if not (0 <= rank <= 9 and 0 <= file <= 8):
            return
        self.cells[rank][file] = piece

    def copy(self) -> "Position":
        return Position(
            cells=[row[:] for row in self.cells],
            side_to_move=self.side_to_move,
            move_num=self.move_num,
            halfmove_clock=self.halfmove_clock,
        )

    def apply_iccs(self, move: str) -> None:
        """Apply a move like 'h2e2' (from_file, from_rank, to_file, to_rank)."""
        m = re.fullmatch(r"([a-i])([0-9])([a-i])([0-9])", move.strip().lower())
        if not m:
            raise ValueError(f"bad ICCS move: {move!r}")
        from_file, from_rank, to_file, to_rank = (
            FILE_NAMES.index(m[1]),
            int(m[2]),
            FILE_NAMES.index(m[3]),
            int(m[4]),
        )
        piece = self.piece_at(from_rank, from_file)
        if piece is None:
            raise ValueError(f"no piece at {m[1]}{m[2]} in FEN {self.to_fen()}")
        self.set_piece(from_rank, from_file, None)
        self.set_piece(to_rank, to_file, piece)
        if self.side_to_move == "b":
            self.move_num += 1
        self.side_to_move = "b" if self.side_to_move == "w" else "w"

    def diff_from(self, other: "Position") -> List[Tuple[int, int, int, int]]:
        """Return list of (from_rank, from_file, to_rank, to_file) tuples inferred
        by comparing this position to `other`. Assumes one or two piece moves."""
        disappeared = []
        appeared = []
        for r in range(10):
            for f in range(9):
                prev = other.piece_at(r, f)
                cur = self.piece_at(r, f)
                if prev is not None and cur is None:
                    disappeared.append((r, f))
                elif prev is None and cur is not None:
                    appeared.append((r, f))
        moves: List[Tuple[int, int, int, int]] = []
        used_to: set = set()
        for from_r, from_f in disappeared:
            moving = other.piece_at(from_r, from_f)
            best: Optional[Tuple[int, int]] = None
            for to_r, to_f in appeared:
                if (to_r, to_f) in used_to:
                    continue
                if self.piece_at(to_r, to_f) == moving:
                    best = (to_r, to_f)
                    break
            if best is None:
                continue
            used_to.add(best)
            moves.append((from_r, from_f, best[0], best[1]))
        return moves

    def __repr__(self) -> str:
        lines = []
        for r in range(10):
            lines.append(" ".join((self.cells[r][f] or "..") for f in range(9)))
        return "\n".join(lines)


START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w"
