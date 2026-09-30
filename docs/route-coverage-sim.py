# -*- coding: utf-8 -*-
"""
航线覆盖仿真（回归验证工具，不需要开游戏）

用 Python 1:1 复刻 FlyTask 的航点规则与 FlyController 的"到达判定 / 补漏"逻辑，
对比「旧规则」和「2.1.0 的规则」在同样转弯能力下对区域内区块的覆盖情况。

2026-09-25 用它复现并验证了"偶尔有一条单区块宽的带没被加载到"这个问题：
  旧规则在 speed=2.0/转向=2.0 时会漏掉 z=5 的一整条单区块宽带（最差 98.45% 且不收敛）；
  2.1.0 的规则在 6 组参数下全部 100%，且飞行顺畅时补漏轮数为 0（零额外开销）。

用法：python tools/route-coverage-sim.py

航线覆盖仿真（新版）：主航线（40 格到达 + 越线兜底 + 区域外扩 1 区块）飞完后，
对没覆盖到的区块再生成"补漏航线"（直接飞过漏点），最多 3 轮。

对比对象：旧版（40 格到达、无外扩、无补漏）——它在转弯迟钝时会漏出一条单区块宽的带。
"""
import math, io, sys

sys.stdout.reconfigure(encoding='utf-8')
CHUNK = 16.0
MARGIN = 1
MAX_FILL = 3
MAIN_REACH = 40.0
FILL_REACH = 12.0


def rows_of(min_cx, max_cx, min_cz, max_cz, R, margin):
    step = max(1, R * 2)
    rminz, rmaxz = min_cz - margin, max_cz + margin
    rows = list(range(rminz, rmaxz, step))
    if not rows or rows[-1] != rmaxz:
        rows.append(rmaxz)
    return (min_cx - margin, max_cx + margin, rows)


def snake_waypoints(min_cx, max_cx, min_cz, max_cz, R, margin):
    rminx, rmaxx, rows = rows_of(min_cx, max_cx, min_cz, max_cz, R, margin)
    wps, l2r = [], True
    for z in rows:
        wps.append((rminx if l2r else rmaxx, z))
        wps.append((rmaxx if l2r else rminx, z))
        l2r = not l2r
    return wps


def waypoints_old(min_cx, max_cx, min_cz, max_cz, R):
    """旧版：行只排到区域边界，无外扩"""
    step = max(1, R * 2)
    wps, l2r, cz = [], True, min_cz
    while cz <= max_cz + step:
        row = min(cz, max_cz)
        if row >= min_cz:
            wps.append((min_cx if l2r else max_cx, row))
            wps.append((max_cx if l2r else min_cx, row))
            l2r = not l2r
            if row == max_cz:
                break
        cz += step
    return wps


class Sim:
    def __init__(self, min_cx, max_cx, min_cz, max_cz, R, speed, turn, crossed=True, fill=True):
        self.min_cx, self.max_cx, self.min_cz, self.max_cz, self.R = min_cx, max_cx, min_cz, max_cz, R
        self.speed, self.turn = speed, turn
        self.crossed, self.fill_enabled = crossed, fill
        self.pos = [(min_cx + max_cx) / 2 * CHUNK, (min_cz + max_cz) / 2 * CHUNK]
        self.yaw = 0.0
        self.covered = set()
        self.wps = []
        self.idx = 0
        self.fill_start = 10 ** 9
        self.fill_rounds = 0
        self.ticks = 0
        self.dist = 0.0

    def mark(self):
        pcx, pcz = int(math.floor(self.pos[0] / CHUNK)), int(math.floor(self.pos[1] / CHUNK))
        for cx in range(pcx - self.R, pcx + self.R + 1):
            if self.min_cx <= cx <= self.max_cx:
                for cz in range(pcz - self.R, pcz + self.R + 1):
                    if self.min_cz <= cz <= self.max_cz:
                        self.covered.add((cx, cz))

    def advance_ok(self, wp):
        wx, wz = wp[0] * CHUNK + 8, wp[1] * CHUNK + 8
        dx, dz = wx - self.pos[0], wz - self.pos[1]
        reach = FILL_REACH if self.idx >= self.fill_start else MAIN_REACH
        if dx * dx + dz * dz <= reach * reach:
            return True
        if not self.crossed or self.idx <= 0:
            return False
        pw = self.wps[self.idx - 1]
        ux, uz = wx - (pw[0] * CHUNK + 8), wz - (pw[1] * CHUNK + 8)
        n = math.hypot(ux, uz)
        if n < 1e-6:
            return False
        return ((self.pos[0] - wx) * ux + (self.pos[1] - wz) * uz) / n >= 0.0

    def build_fill(self):
        if not self.fill_enabled or self.fill_rounds >= MAX_FILL:
            return False
        missing = [(cx, cz) for cx in range(self.min_cx, self.max_cx + 1)
                   for cz in range(self.min_cz, self.max_cz + 1) if (cx, cz) not in self.covered]
        if not missing:
            return False
        self.fill_rounds += 1
        self.fill_start = min(self.fill_start, len(self.wps))
        missing.sort(key=lambda c: (c[1], c[0]))
        l2r, i = True, 0
        while i < len(missing):
            z = missing[i][1]
            xs = [c[0] for c in missing if c[1] == z]
            i += len(xs)
            x1, x2 = (min(xs), max(xs)) if l2r else (max(xs), min(xs))
            self.wps.append((x1, z))
            if x2 != x1:
                self.wps.append((x2, z))
            l2r = not l2r
        return True

    def run(self, max_ticks=300000):
        self.wps = self._initial()
        while self.ticks < max_ticks:
            self.ticks += 1
            self.mark()
            if self.idx >= len(self.wps) and not self.build_fill():
                break
            if self.idx >= len(self.wps):
                continue
            wp = self.wps[self.idx]
            wx, wz = wp[0] * CHUNK + 8, wp[1] * CHUNK + 8
            target = math.degrees(math.atan2(-(wx - self.pos[0]), wz - self.pos[1]))
            diff = (target - self.yaw + 180) % 360 - 180
            self.yaw += max(-self.turn, min(self.turn, diff))
            rad = math.radians(self.yaw)
            self.pos[0] += -math.sin(rad) * self.speed
            self.pos[1] += math.cos(rad) * self.speed
            self.dist += self.speed
            guard = 0
            while self.idx < len(self.wps) and guard < 8:
                if not self.advance_ok(self.wps[self.idx]):
                    break
                self.idx += 1
                guard += 1
        return self.covered

    def _initial(self):
        return snake_waypoints(self.min_cx, self.max_cx, self.min_cz, self.max_cz, self.R, MARGIN)


def covered_old(min_cx, max_cx, min_cz, max_cz, R, speed, turn):
    sim = Sim(min_cx, max_cx, min_cz, max_cz, R, speed, turn, crossed=False, fill=False)
    sim._initial = lambda: waypoints_old(min_cx, max_cx, min_cz, max_cz, R)
    cov = sim.run()
    return cov, sim


def report(tag, cov, sim, min_cx, max_cx, min_cz, max_cz):
    total = (max_cx - min_cx + 1) * (max_cz - min_cz + 1)
    missing = [(cx, cz) for cx in range(min_cx, max_cx + 1) for cz in range(min_cz, max_cz + 1)
               if (cx, cz) not in cov]
    strip = ""
    if missing:
        zs = sorted({c[1] for c in missing})
        strip = " z=%s" % (zs if len(zs) < 8 else "%d 行" % len(zs))
    print("  %-26s 覆盖 %5d/%5d  %6.2f%%  漏 %3d  补漏轮数=%d  tick=%6d 距离=%7.0f%s"
          % (tag, len(cov), total, 100.0 * len(cov) / total, len(missing),
             sim.fill_rounds, sim.ticks, sim.dist, strip))
    return missing


if __name__ == "__main__":
    print("区域 41x41 区块，markRadius=6（行距 12）、区域外扩 1 区块：")
    min_cx, max_cx, min_cz, max_cz, R = 0, 40, 0, 40, 6
    for speed, turn in ((2.0, 4.0), (2.5, 3.0), (1.5, 6.0), (2.0, 2.0), (2.2, 1.5), (3.0, 2.5)):
        print("\n--- speed=%.1f 格/tick, 最大转向=%.1f°/tick" % (speed, turn))
        cov, sim = covered_old(min_cx, max_cx, min_cz, max_cz, R, speed, turn)
        report("旧版(无外扩/无补漏)", cov, sim, min_cx, max_cx, min_cz, max_cz)
        sim2 = Sim(min_cx, max_cx, min_cz, max_cz, R, speed, turn, crossed=True, fill=True)
        cov2 = sim2.run()
        report("新版(外扩+越线兜底+补漏)", cov2, sim2, min_cx, max_cx, min_cz, max_cz)
