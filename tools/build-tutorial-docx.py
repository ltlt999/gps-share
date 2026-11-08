"""把《使用教程》生成一份面向客户的 Word 文档。

中文必须显式设置 w:eastAsia 字体，否则 Word 会用 fallback 字体渲染，
中文看起来会「不对劲」。所有 run 都过 set_font() 就是为了这个。
"""
import os
import docx
from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Pt, RGBColor, Cm

BODY_FONT = "微软雅黑"
MONO_FONT = "Consolas"
ACCENT = RGBColor(0x0B, 0x72, 0x2E)
DIM = RGBColor(0x66, 0x66, 0x66)
WARN = RGBColor(0xB4, 0x53, 0x09)

doc = Document()

# 页边距
for section in doc.sections:
    section.top_margin = Cm(2.2)
    section.bottom_margin = Cm(2.2)
    section.left_margin = Cm(2.4)
    section.right_margin = Cm(2.4)

# 正文默认样式
normal = doc.styles["Normal"]
normal.font.name = BODY_FONT
normal.font.size = Pt(10.5)
normal._element.rPr.rFonts.set(qn("w:eastAsia"), BODY_FONT)
normal.paragraph_format.space_after = Pt(6)
normal.paragraph_format.line_spacing = 1.4


def set_font(run, name=BODY_FONT, size=10.5, bold=False, color=None, italic=False):
    run.font.name = name
    run.font.size = Pt(size)
    run.bold = bold
    run.italic = italic
    if color is not None:
        run.font.color.rgb = color
    run._element.rPr.rFonts.set(qn("w:eastAsia"), name)
    return run


def h1(text):
    p = doc.add_heading(level=1)
    p.paragraph_format.space_before = Pt(18)
    p.paragraph_format.space_after = Pt(8)
    set_font(p.add_run(text), size=17, bold=True, color=RGBColor(0x11, 0x11, 0x11))
    return p


def h2(text):
    p = doc.add_heading(level=2)
    p.paragraph_format.space_before = Pt(13)
    p.paragraph_format.space_after = Pt(6)
    set_font(p.add_run(text), size=13.5, bold=True, color=RGBColor(0x22, 0x22, 0x22))
    return p


def p(text="", bold=False, color=None, size=10.5, indent=0.0):
    """支持 **加粗** 片段。"""
    para = doc.add_paragraph()
    if indent:
        para.paragraph_format.left_indent = Cm(indent)
    for i, chunk in enumerate(text.split("**")):
        if not chunk:
            continue
        set_font(para.add_run(chunk), size=size,
                 bold=(bold or i % 2 == 1), color=color)
    return para


def bullet(text, indent=0.6):
    para = doc.add_paragraph(style="List Bullet")
    para.paragraph_format.left_indent = Cm(indent)
    para.paragraph_format.space_after = Pt(3)
    for i, chunk in enumerate(text.split("**")):
        if not chunk:
            continue
        set_font(para.add_run(chunk), bold=(i % 2 == 1))
    return para


def numbered(text, indent=0.6):
    para = doc.add_paragraph(style="List Number")
    para.paragraph_format.left_indent = Cm(indent)
    para.paragraph_format.space_after = Pt(3)
    for i, chunk in enumerate(text.split("**")):
        if not chunk:
            continue
        set_font(para.add_run(chunk), bold=(i % 2 == 1))
    return para


def code(lines, indent=0.6):
    para = doc.add_paragraph()
    para.paragraph_format.left_indent = Cm(indent)
    para.paragraph_format.space_before = Pt(6)
    para.paragraph_format.space_after = Pt(8)
    para.paragraph_format.line_spacing = 1.15
    set_font(para.add_run("\n".join(lines)), name=MONO_FONT, size=9.5)
    return para


def quote(text, color=DIM):
    para = doc.add_paragraph()
    para.paragraph_format.left_indent = Cm(0.6)
    para.paragraph_format.space_before = Pt(4)
    para.paragraph_format.space_after = Pt(8)
    for i, chunk in enumerate(text.split("**")):
        if not chunk:
            continue
        set_font(para.add_run(chunk), size=9.5, bold=(i % 2 == 1), color=color)
    return para


def warn(text):
    return quote(text, color=WARN)


def table(header, rows, widths=None):
    t = doc.add_table(rows=1, cols=len(header))
    t.style = "Table Grid"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, text in enumerate(header):
        cell = t.rows[0].cells[i]
        cell.text = ""
        set_font(cell.paragraphs[0].add_run(text), size=10, bold=True)
    for row in rows:
        cells = t.add_row().cells
        for i, text in enumerate(row):
            cells[i].text = ""
            set_font(cells[i].paragraphs[0].add_run(text), size=10)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)
    return t


# ============================================================
#  文档正文
# ============================================================

title = doc.add_paragraph()
title.alignment = WD_ALIGN_PARAGRAPH.CENTER
set_font(title.add_run("GPS共享 · 使用教程"), size=22, bold=True)
sub = doc.add_paragraph()
sub.alignment = WD_ALIGN_PARAGRAPH.CENTER
sub.paragraph_format.space_after = Pt(20)
set_font(sub.add_run("万物皆可买　项目主页：https://github.com/ltlt999/gps-share"), size=10, color=DIM)

# ---- 一 ----
h1("一、这个软件能干什么？")
p("**一句话：把手机的定位，分享给没有定位的设备。**")
p("有些车机、平板、笔记本本身没有 GPS 模块，导航用不了。装上这个软件后：")
code([
    "你的手机（有 GPS）  ──────►  车机 / 平板（没 GPS）",
    "     负责定位                    把手机的位置当成自己的",
])
p("车机上的高德、百度地图就能正常导航了，位置跟着手机走。")
p("**手机和平板上装的是同一个软件**，只是一个选「共享」、一个选「接收」。")

# ---- 二 ----
h1("二、你要准备什么")
bullet("**一部手机**（有 GPS 的普通安卓手机都行，安卓 7.0 以上）")
bullet("**一台没有定位的设备**：车机、平板、笔记本等")
bullet("如果是给车机 / 平板用，**那台设备也要能装软件**（安卓 7.0 以上）")
warn("**先确认一件事**：接收的那台设备，能不能进「开发者选项」？"
     "教程第五节会讲怎么开。**如果那台车机锁死了进不去，这个软件就用不了**——"
     "这一点请先确认，免得白买。")

# ---- 三 ----
h1("三、安装")
numbered("把 `GPS共享` 的安装包（APK 文件）传到设备上")
numbered("点击这个文件")
numbered("如果弹出「**禁止安装未知应用**」，点「**设置**」→ 允许本文件管理器安装 → 返回继续")
numbered("安装完成后，桌面会出现「**GPS共享**」图标")
quote("手机和平板**都要装**。")

# ---- 四 ----
h1("四、手机（共享端）怎么设置")
p("打开「GPS共享」，你会看到顶部有两个开关：**角色** 和 **传输方式**。")

h2("4.1　选角色")
p("**角色** 选 **「共享 GPS」**（把本机的定位发出去）。")

h2("4.2　选传输方式")
p("**传输方式** 有两个选择，**选哪个取决于你的需求**：")
table(["选哪个", "什么时候选"],
      [["WiFi（推荐）", "车机/平板也要上网（在线导航、听歌）——选它，一份热点同时供网和供定位"],
       ["蓝牙", "只想省电、车机不需要上网"]])
quote("拿不准就选 **WiFi**。")

h2("4.3　开始")
p("**如果选了 WiFi：**")
numbered("先在手机「**系统设置 → 个人热点**」里把热点打开")
numbered("回到「GPS共享」，点中间的大圆按钮「**开始传输**」")
numbered("界面上会显示一个「**热点地址**」（形如 192.168.43.1）——**记下它，下一步要用**")
p("**如果选了蓝牙：**")
numbered("先和车机/平板在「系统设置 → 蓝牙」里**配对**")
numbered("回到「GPS共享」，点「**开始传输**」")
p("点完之后，顶部会出现一条常驻通知，说明正在运行。**这时手机可以锁屏、可以放兜里。**")

# ---- 五 ----
h1("五、车机 / 平板（接收端）怎么设置")

h2("5.1　⚠ 关键一步：开启「模拟位置」")
p("**这一步不做，导航软件看不到位置。** 请照着做：")
numbered("打开「**设置**」→「**关于手机**」（或「关于平板」）")
numbered("找到「**版本号**」，**连续快速点 7 次**")
numbered("屏幕会提示「**您已进入开发者模式**」")
numbered("返回「设置」，找到「**开发者选项**」（有的在「系统」或「更多设置」里）")
numbered("找到「**选择模拟位置信息应用**」")
numbered("点进去，选「**GPS共享**」")
warn("找不到「选择模拟位置信息应用」？部分品牌（小米、华为等）把它藏在"
     "「更多设置」或「USB 调试（安全设置）」分类下，翻一翻就能找到。")

h2("5.2　打开软件、选接收")
p("打开「GPS共享」，**角色** 选 **「接收 GPS」**，**传输方式** 和手机保持一致。")

h2("5.3　连接")
p("**如果手机用的是 WiFi：**")
numbered("先让这台车机/平板**连上手机的那个热点**（和手机开的是同一个）")
numbered("回到「GPS共享」，稍等几秒，「**发现的发送端**」列表里会出现你的手机")
numbered("**点一下它**，地址和端口会自动填好")
numbered("点中间的大圆按钮「**连接**」")
quote("列表里没出现手机？手动填：在「**手动连接**」里把第 4.3 步记下的"
      "**热点地址**填进「地址」，端口保持默认的 10110，再点「连接」。")
p("**如果手机用的是蓝牙：**")
numbered("先和手机在「系统设置 → 蓝牙」里**配对**")
numbered("回到「GPS共享」，「已配对设备」列表里点选你的手机（出现 ✓）")
numbered("点「**连接**」")

h2("5.4　确认成功")
p("状态变成「**已连接**」，下面的「运行数据」开始显示经纬度、速度——**成功了**。")
p("现在打开车机上的导航软件（高德 / 百度等），应该能正常定位了。")
warn("**导航里卫星数显示 0？这是正常的。** 安卓系统的限制，模拟位置只能传位置、"
     "传不了卫星图。**不影响导航使用**。想看卫星，在本软件的「卫星」页看，那里是正常的。")

# ---- 六 ----
h1("六、常见问题")
qa = [
    ("导航软件里位置不动 / 一直没反应？",
     "检查第五节的「模拟位置」有没有设置好——**这是最常见的原因**。"
     "到「设置 → 开发者选项 → 选择模拟位置信息应用」确认选的是「GPS共享」。"),
    ("接收端列表里找不到手机？",
     "① 确认手机那边已经点了「开始传输」；② 确认车机/平板连的是**手机的热点**；"
     "③ 还不行就用手动填地址（见 5.3）。"),
    ("手机锁屏后还管用吗？", "管用。传输在后台运行，屏幕可以关。"),
    ("车机放了一晚上，第二天还能用吗？", "能。软件会自动重连，不用管它。"),
    ("导航里卫星数是 0，是不是坏了？",
     "不是。安卓系统的限制，只影响「显示卫星数」，**不影响导航**（见 5.4 的说明）。"),
    ("换手机 / 换车机了怎么办？",
     "直接装上就能用，软件免费开源，不需要配置。"),
    ("想改发送频率、关掉某些数据？",
     "「设置」页里有：频率滑块（1–10 次/秒），以及五类数据的开关。"
     "**不懂就别动**，默认值就是最通用的。"),
]
for question, answer in qa:
    p("**Q：" + question + "**")
    p("A：" + answer, indent=0.6)

# ---- 七 ----
h1("七、反馈问题")
bullet("项目主页：**https://github.com/ltlt999/gps-share**")
bullet("问题反馈：**https://github.com/ltlt999/gps-share/issues**")
p("遇到问题请在 issue 里附上这两样，能快很多：")
numbered("你在做什么操作")
numbered("「GPS共享」→「日志」页的截图（或点「导出」把日志文件发过来）")

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs",
                   "GPS共享-使用教程.docx")
doc.save(out)
print("已生成:", out)
