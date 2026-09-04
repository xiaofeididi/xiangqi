"""Chinese notation for xiangqi moves (炮二平五 / 马8进7 style)."""

from __future__ import annotations

from typing import List, Optional

from .position import Position


CN_NUM = {0: "一", 1: "二", 2: "三", 3: "四", 4: "五",
          5: "六", 6: "七", 7: "八", 8: "九"}
TYPE_CN = {"r": "车", "n": "马", "b": "相", "a": "仕",
           "k": "帅", "c": "炮", "p": "兵"}
TYPE_CN_B = {"r": "车", "n": "马", "b": "象", "a": "士",
             "k": "将", "c": "炮", "p": "卒"}


def _rank_label(rank: int, side: str) -> str:
    # red (w) sees ranks 0-9 as 10-1 bottom-up? Actually standard xiangqi FEN has
    # rank 0 = black top. Red counts ranks 9..0 as 1..10 (bottom to top).
    if side == "w":
        return CN_NUM[9 - rank]
    return CN_NUM[rank]


def move_to_chinese(pos: Position, move: str) -> str:
    """Convert 'h2e2' to '炮二平五' or similar based on the position before the move."""
    from_file, from_rank, to_file, to_rank = _parse_iccs(move)
    piece = pos.piece_at(from_rank, from_file)
    if piece is None:
        return move
    side = piece[0]
    ptype = piece[1]
    name = (TYPE_CN if side == "w" else TYPE_CN_B)[ptype]

    from_label = _rank_label(from_rank, side)
    to_label = _rank_label(to_rank, side)

    # horizontal move (same rank): 平 + destination file number
    if from_rank == to_rank:
        return f"{name}{from_label}平{_file_label(to_file, side)}"
    # vertical/horizontal-vertical move
    forward = (side == "w" and to_rank < from_rank) or (side == "b" and to_rank > from_rank)
    verb = "进" if forward else "退"
    # if same file, use rank delta; otherwise use destination file
    if from_file == to_file:
        delta = abs(to_rank - from_rank)
        # red counts downward rank index as going up; use label of dest rank
        return f"{name}{from_label}{verb}{_file_label(to_file, side)}"
    # diagonal move (horse/elephant): use destination file
    return f"{name}{from_label}{verb}{_file_label(to_file, side)}"


def _file_label(file_idx: int, side: str) -> str:
    # red numbers files right-to-left: file 'a'(0) = 9, 'i'(8) = 1
    if side == "w":
        return CN_NUM[8 - file_idx]
    # black numbers left-to-right: file 'a'(0) = 1
    return CN_NUM[file_idx]


def _parse_iccs(move: str):
    from_file, from_rank, to_file, to_rank = (
        ord(move[0]) - ord("a"), int(move[1]),
        ord(move[2]) - ord("a"), int(move[3]),
    )
    return from_file, from_rank, to_file, to_rank
