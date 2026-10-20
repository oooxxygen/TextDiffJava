# -*- coding: utf-8 -*-
"""构造 CTIS 对抗比对 B 侧数据（CtisExampleB）。

以 CtisExampleA 为基线，注入已知差异（每处差异均可独立验证）：
  CHO（OAIF，22 栏，无 ~~ 动态栏位 → 普通逐列比对路径）：
    L1  栏位 5 值差异（12325678 → 12325679）        → diff 列 5
  PNT（BPAF，111 栏，栏位 107 = DYNAMIC_MESSAGE_01 → 虚拟子列展开路径）：
    L1  子栏位 1C 值差异（…339 → …340）             → diff 107-1C
    L2  子栏位 1B 值差异（1018 → 1019）             → diff 107-1B
    L3  子栏位 1C 整段缺失（B 侧无该编号）          → diff 107-1C（单侧缺失）
    L4  完全不变                                     → equal
    L5  子栏位顺序对调（1B/1C 互换）                → diff（编号配对顺序无关，但栏位不去
                                                      前后空格：尾部补位随"最后一个分段"归属，
                                                      对调后补位从 1C 换挂到 1B，两子值判差）
    L6  静态前缀 S→T + 栏位 10 值差异               → diff 自然段 107-1 + 真实列 10
    L7  仅 B 侧新增行（f2 换新唯一号）              → only_b
预期：CHO equal=3 diff=1；PNT equal=1（L4） diff=5（L1/L2/L3/L5/L6） only_b=1（L7）
"""
import os

SRC = r"O:\CodeRepos\ExampleData\OriData\CtisExampleA"
DST = r"O:\CodeRepos\ExampleData\OriData\CtisExampleB"
CHO = "01A1170D.CHO.ug.202612311"
PNT = "01A1230D.PNT.ug.202704301"


def read(name):
    with open(os.path.join(SRC, name), encoding="utf-8", newline="") as f:
        return f.read()


def write(name, text):
    os.makedirs(DST, exist_ok=True)
    with open(os.path.join(DST, name), "w", encoding="utf-8", newline="") as f:
        f.write(text)


def build_cho():
    lines = read(CHO).split("\n")
    out = []
    for l in lines:
        if l.startswith("|||||") or not l:
            out.append(l)
            continue
        f = l.split(" | ")
        if f[2].startswith("TI26123100000001"):  # L1：栏位 5 差异
            f[4] = "12325679"
            l = " | ".join(f)
        out.append(l)
    write(CHO, "\n".join(out))


def build_pnt():
    lines = read(PNT).split("\n")
    data, trailer = [], []
    for l in lines:
        (trailer if (l.startswith("|||||") or not l) else data).append(l)
    only_b = None
    for i, l in enumerate(data):
        f = l.split(" | ")
        key = f[1]
        dyn = f[106]
        if key == "00100002400386481":  # L1：1C 值差异
            f[106] = dyn.replace("~~1C23053339", "~~1C23053340")
        elif key == "00100002400684954":  # L2：1B 值差异
            f[106] = dyn.replace("~~1B20261018", "~~1B20261019")
        elif key == "00100002400424973":  # L3：1C 整段缺失（保持 1000 定长）
            f[106] = "~~1B20261018".ljust(1000)
        elif key == "00100002400197472":  # L5：子栏位顺序对调 → 应判等
            f[106] = dyn.replace("~~1B20261017~~1C09344963", "~~1C09344963~~1B20261017")
        elif key == "00100002400340231":  # L6：静态前缀 S→T + 栏位 10 值差异
            f[106] = dyn.replace("S", "T", 1)
            f[9] = ("0" if not f[9].startswith("0") else "1") + f[9][1:]
        data[i] = " | ".join(f)
        # key == 00100002400102423（L4）：保持不变，并复制为仅 B 侧新增行
        if key == "00100002400102423" and only_b is None:
            nf = list(f)
            nf[1] = "00100002400999999"
            only_b = " | ".join(nf)
    out = data + ([only_b] if only_b else []) + trailer
    write(PNT, "\n".join(out))


if __name__ == "__main__":
    build_cho()
    build_pnt()
    for n in (CHO, PNT):
        p = os.path.join(DST, n)
        print(n, os.path.getsize(p), "bytes")
    print("done ->", DST)
