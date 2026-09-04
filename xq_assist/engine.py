"""Pikafish UCCI engine wrapper."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
import threading
from dataclasses import dataclass
from typing import List, Optional, Tuple


@dataclass
class EngineResult:
    bestmove: str
    score_cp: Optional[int] = None      # centipawn score (positive = side-to-move better)
    mate_in: Optional[int] = None       # if mate found, moves to mate
    depth: int = 0
    pv: List[str] = None

    def __post_init__(self):
        if self.pv is None:
            self.pv = []


class Pikafish:
    def __init__(self, engine_path: str, nnue_path: Optional[str] = None,
                 threads: int = 2, hash_mb: int = 128, elo: Optional[int] = None):
        self.engine_path = engine_path
        self.nnue_path = nnue_path
        self.threads = threads
        self.hash_mb = hash_mb
        self.elo = elo
        self.proc: Optional[subprocess.Popen] = None
        self._lock = threading.Lock()

    def start(self) -> None:
        if self.proc and self.proc.poll() is None:
            return
        env = os.environ.copy()
        if self.nnue_path:
            env["PIKAFISH_NNUE"] = self.nnue_path
        self.proc = subprocess.Popen(
            [self.engine_path],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, bufsize=1, env=env,
        )
        self._send("uci")
        self._wait_for("uciok")
        self._send(f"setoption name Threads value {self.threads}")
        self._send(f"setoption name Hash value {self.hash_mb}")
        if self.elo is not None:
            self._send(f"setoption name UCI_LimitStrength value true")
            self._send(f"setoption name UCI_Elo value {self.elo}")
        self._send("isready")
        self._wait_for("readyok")

    def _send(self, cmd: str) -> None:
        if self.proc and self.proc.stdin:
            self.proc.stdin.write(cmd + "\n")
            self.proc.stdin.flush()

    def _read_line(self, timeout: float = 10.0) -> Optional[str]:
        # synchronous read with simple timeout via select is not portable;
        # use readline with a thread-based timeout pattern
        result: List[Optional[str]] = [None]

        def _read():
            try:
                result[0] = self.proc.stdout.readline().rstrip("\n")
            except Exception:
                pass

        t = threading.Thread(target=_read, daemon=True)
        t.start()
        t.join(timeout)
        return result[0]

    def _wait_for(self, token: str, timeout: float = 15.0) -> None:
        import time
        start = time.time()
        while time.time() - start < timeout:
            line = self._read_line(timeout=timeout - (time.time() - start))
            if line is None:
                break
            if token in line:
                return
        raise TimeoutError(f"engine did not send {token!r} in {timeout}s")

    def analyze(self, fen: str, movetime_ms: int = 1000, depth: Optional[int] = None) -> EngineResult:
        with self._lock:
            self._send("stop")
            self._send(f"position fen {fen}")
            if depth:
                self._send(f"go depth {depth}")
            else:
                self._send(f"go movetime {movetime_ms}")
            bestmove = None
            score = None
            mate = None
            last_depth = 0
            pv: List[str] = []
            import time
            start = time.time()
            timeout = max(3.0, (movetime_ms / 1000) * 3 + 2)
            while time.time() - start < timeout:
                line = self._read_line(timeout=1.0)
                if line is None:
                    continue
                if line.startswith("info") and " pv " in line:
                    m = re.search(r"depth (\d+)", line)
                    if m:
                        last_depth = int(m[1])
                    m = re.search(r"score (cp|mate) (-?\d+)", line)
                    if m:
                        if m[1] == "cp":
                            score = int(m[2])
                            mate = None
                        else:
                            mate = int(m[2])
                            score = None
                    m = re.search(r" pv (.+)", line)
                    if m:
                        pv = m[1].split()
                if line.startswith("bestmove"):
                    bestmove = line.split()[1] if len(line.split()) > 1 else ""
                    break
            return EngineResult(
                bestmove=bestmove or "", score_cp=score, mate_in=mate,
                depth=last_depth, pv=pv,
            )

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            self._send("quit")
            try:
                self.proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.proc.kill()

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, *args):
        self.stop()
