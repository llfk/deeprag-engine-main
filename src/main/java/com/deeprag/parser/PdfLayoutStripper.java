package com.deeprag.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 剔除版面噪声的 PDF 文本提取器
 * <p>
 * 在 {@link PDFTextStripper} 的文本片段回调上接入，利用每个字符的坐标、字号与旋转角剔除
 * 页眉、页脚、页码与水印，避免这些每页重复出现的噪声进入正文并污染向量检索。
 * <p>
 * 采用两遍扫描：第一遍只收集版面信息并做跨页统计，第二遍输出时丢弃噪声行。
 * 之所以要两遍，是因为「某行是否每页都出现」必须看完全部页才能判定。
 * <p>
 * 统计与判定都以「视觉行」为单位：PDFBox 在文本间隙处会把一行拆成多次回调，
 * 若按片段统计，页眉里的「基于」这类常见词会被正文污染，永远判不出重复。
 */
public class PdfLayoutStripper extends PDFTextStripper {

    /**
     * 页眉带/页脚带各占页高的比例。
     * A4（页高 842pt）下约 67pt：实测页眉距顶约 6.5%、正文首行约 12.3%，8% 正好落在两者之间。
     */
    private static final float BAND_RATIO = 0.08f;
    /**
     * 页眉页脚判定：归一化文本需覆盖的最少页数比例。
     * 不要求页页都有，封面、章首页常缺页眉页脚；60% 能容忍这类缺失，又能排除偶发重复。
     */
    private static final float HEADER_FOOTER_COVER = 0.6f;
    /**
     * 水印判定：归一化文本需覆盖的最少页数比例。
     * 水印几乎每页都印，所以卡得比页眉页脚严，只留出个别页面缺失的余地。
     */
    private static final float WATERMARK_COVER = 0.9f;
    /**
     * 水印判定：各页 y 坐标允许的最大波动（pt）。
     * 水印由同一套模板生成，各页位置几乎完全一致；2pt 只用于吸收提取时的微小偏差。
     */
    private static final float WATERMARK_Y_TOLERANCE = 2f;
    /**
     * 水印判定：字号需大于正文中位字号的倍数。
     * 1.5 倍是保守下限：既能命中常见的大字水印，又不会误伤每页重复的小字号模板行（如表格表头）。
     */
    private static final float WATERMARK_SIZE_FACTOR = 1.5f;
    /**
     * 少于该页数不做跨页统计。
     * 只有 2 页时「重复出现」不足以证明是模板内容，3 页起统计才有说服力。
     */
    private static final int MIN_PAGES_FOR_CROSS_PAGE = 3;
    /**
     * 参与噪声统计的行长度上限，更长的行一律视为正文。
     * 页眉页脚页码都是短行；超过 120 字符基本可断定是正文段落，故不参与统计、也不会被剔除。
     */
    private static final int MAX_NOISE_LENGTH = 120;
    /**
     * 旋转判定容差（度）。
     * 横排文本的方向角为 0，1 度用于吸收浮点误差。
     */
    private static final float ROTATION_TOLERANCE = 1f;
    /**
     * 旋转文本占比超过该值时视为竖排文档，关闭旋转剔除。
     * 竖排文档几乎 100% 旋转，而横排文档里的旋转水印通常不足 10%，50% 足以把两者分开。
     */
    private static final float VERTICAL_DOC_RATIO = 0.5f;
    /**
     * 同一视觉行的 y 容差（pt）：y 差在此范围内的片段视为同一行。
     * 同一行内字号或字体不同的片段基线会有微小差异，3pt 用于吸收它。
     */
    private static final float LINE_Y_TOLERANCE = 3f;

    /** true=处于第一遍收集阶段：只统计版面信息，不产生输出 */
    private boolean collecting;

    /** 是否启用旋转剔除；整篇过半文本都是旋转时会在 analyze 里被关掉，避免误删竖排文档 */
    private boolean dropRotated;

    /** 收集到的行总数，用于计算旋转文本占比 */
    private int lineCount;

    /** 其中旋转的行数，占比超过 VERTICAL_DOC_RATIO 即判定为竖排文档 */
    private int rotatedCount;

    /** 归一化文本 → 跨页出现情况 */
    private final Map<String, KeyStat> stats = new HashMap<>();

    /** 判定为页眉页脚或水印的归一化文本 */
    private final Set<String> noise = new HashSet<>();

    /** 用于求正文中位字号 */
    private final List<Float> fontSizes = new ArrayList<>();

    /** 当前页的文本片段缓存：同一行会被 PDFBox 拆成多段，所以要攒到页末再拼回整行 */
    private final List<Fragment> fragments = new ArrayList<>();

    /** 构造函数本身不做初始化，可能抛出的 IOException 来自父类 PDFTextStripper */
    public PdfLayoutStripper() throws IOException {
        super();
    }

    /**
     * 逐页提取文本并剔除版面噪声
     *
     * @param doc 已加载的 PDF 文档
     * @return 每页一段文本，第 i 个元素对应第 i+1 页
     */
    public List<String> extractPages(PDDocument doc) throws IOException {
        // 第一遍：只收集版面信息（writeString 被拦截，不产生输出）
        collecting = true;
        // 借这次遍历，让 PDFBox 回调我们重写的 writeString
        super.getText(doc);
        collecting = false;
        analyze(doc.getNumberOfPages());

        // 第二遍：逐页输出，输出时丢弃噪声行
        List<String> pages = new ArrayList<>();
        int pageCount = doc.getNumberOfPages();
        for (int i = 1; i <= pageCount; i++) {
            setStartPage(i);
            setEndPage(i);
            // 剔除整行后页首/页尾会留下空行，这里去掉
            pages.add(super.getText(doc).strip());
        }
        return pages;
    }

    /**
     * 提取全文并剔除版面噪声
     *
     * @param doc 已加载的 PDF 文档
     * @return 清理后的文本，页与页之间以换行分隔
     */
    public String extract(PDDocument doc) throws IOException {
        return String.join("\n", extractPages(doc));
    }

    /** PDFBox 每次回调给的是「行内的一段文本」而不是整行（列间隙处会分成多次回调），先缓存起来 */
    @Override
    protected void writeString(String text, List<TextPosition> positions) throws IOException {
        if (positions.isEmpty()) {
            super.writeString(text, positions);
            return;
        }
        String line = text.trim();
        if (line.isEmpty()) {
            return;
        }
        TextPosition first = positions.get(0);
        fragments.add(new Fragment(line, first.getXDirAdj(), first.getYDirAdj(),
                first.getFontSizeInPt(), first.getPageHeight(), !isRotated(first)));
    }

    /** 页末把缓存拼回视觉行：第一遍逐行统计，第二遍逐行判定并输出 */
    @Override
    protected void writePageEnd() throws IOException {
        for (List<Fragment> line : groupVisualLines()) {
            if (collecting) {
                collectLine(line);
            } else if (!shouldDrop(line)) {
                super.writeString(lineText(line));
                super.writeString("\n");
            }
        }
        fragments.clear();
        super.writePageEnd();
    }

    /**
     * 把片段拼回视觉行：按 y 聚成行（y 差在 LINE_Y_TOLERANCE 内视为同一行），行内按 x 从左到右排。
     * PDFBox 在文本间隙处会把一行拆成多次回调，所以「一行」必须在这里重新组装出来。
     */
    private List<List<Fragment>> groupVisualLines() {
        List<Fragment> sorted = new ArrayList<>(fragments);
        sorted.sort(Comparator.comparingDouble(Fragment::y).thenComparingDouble(Fragment::x));
        List<List<Fragment>> lines = new ArrayList<>();
        for (Fragment fragment : sorted) {
            List<Fragment> last = lines.isEmpty() ? null : lines.get(lines.size() - 1);
            if (last != null && Math.abs(last.get(0).y() - fragment.y()) <= LINE_Y_TOLERANCE) {
                last.add(fragment);
            } else {
                List<Fragment> line = new ArrayList<>();
                line.add(fragment);
                lines.add(line);
            }
        }
        return lines;
    }

    /** 整行的文本：行内片段之间用单个空格连接，与原行输出保持一致 */
    private static String lineText(List<Fragment> line) {
        StringBuilder text = new StringBuilder();
        for (Fragment fragment : line) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(fragment.text());
        }
        return text.toString();
    }

    /** 第一遍：记录这一行的页、y 坐标、字号与旋转角，供 analyze 做跨页统计 */
    private void collectLine(List<Fragment> line) {
        String text = lineText(line);
        Fragment first = line.get(0);
        lineCount++;
        fontSizes.add(first.fontSize());
        if (!first.horizontal()) {
            rotatedCount++;
        }
        if (text.length() > MAX_NOISE_LENGTH) {
            return;
        }

        KeyStat stat = stats.computeIfAbsent(normalize(text), k -> new KeyStat());
        int page = getCurrentPageNo();
        stat.pages.add(page);
        stat.maxFontSize = Math.max(stat.maxFontSize, first.fontSize());
        float y = first.y();
        stat.minY = Math.min(stat.minY, y);
        stat.maxY = Math.max(stat.maxY, y);
        if (isInBand(y, first.pageHeight())) {
            stat.bandPages.add(page);
        }
    }

    /**
     * 判定哪些内容属于版面噪声：第一遍收集完成后调用一次，结果供第二遍丢弃时查询。
     * <p>
     * 产出两个结果：
     * <ol>
     *   <li><b>noise</b> —— 需要丢弃的「归一化文本」集合，满足以下任一条即入选：
     *     <ul>
     *       <li>页眉 / 页脚 / 页码：出现的页数 ≥ 总页数×{@code HEADER_FOOTER_COVER}（60%），并且每次出现都落在页首或页尾带内</li>
     *       <li>水印：出现的页数 ≥ 总页数×{@code WATERMARK_COVER}（90%），各页y坐标波动不超过{@code WATERMARK_Y_TOLERANCE}（2pt），
     *           并且字号大于正文中位字号的 {@code WATERMARK_SIZE_FACTOR}（1.5）倍。</li>
     *     </ul>
     *   </li>
     *   <li><b>dropRotated</b> —— 是否丢弃所有旋转文本。整篇旋转行占比达到{@code VERTICAL_DOC_RATIO}（50%）时判定为竖排文档，
     *          关闭这一条，否则竖排正文会被整篇当成水印删掉。</li>
     * </ol>
     *
     * @param pageCount 文档总页数，用于把「出现的页数」换算成覆盖率
     */
    private void analyze(int pageCount) {
        // 竖排文档保护：整篇过半文本都是旋转的，说明旋转是正常排版而不是水印
        dropRotated = lineCount > 0 && (float) rotatedCount / lineCount < VERTICAL_DOC_RATIO;
        if (pageCount < MIN_PAGES_FOR_CROSS_PAGE) {
            return;
        }

        float medianFontSize = median(fontSizes);
        for (Map.Entry<String, KeyStat> entry : stats.entrySet()) {
            KeyStat stat = entry.getValue();
            if (stat.pages.size() < 2) {
                continue;
            }
            // 页眉/页脚/页码：数字归一化后覆盖多数页面，且每次出现都在页首页尾带内
            if (stat.pages.size() >= pageCount * HEADER_FOOTER_COVER
                    && stat.bandPages.size() == stat.pages.size()) {
                noise.add(entry.getKey());
                continue;
            }
            // 水印：几乎每页都出现在同一位置，且字号明显大于正文
            if (stat.pages.size() >= pageCount * WATERMARK_COVER
                    && stat.maxY - stat.minY <= WATERMARK_Y_TOLERANCE
                    && stat.maxFontSize > medianFontSize * WATERMARK_SIZE_FACTOR) {
                noise.add(entry.getKey());
            }
        }
    }

    /** 第二遍：判断这一行该不该丢——整行是旋转的，或命中 analyze 判出的噪声集合 */
    private boolean shouldDrop(List<Fragment> line) {
        return (dropRotated && !line.get(0).horizontal()) || noise.contains(normalize(lineText(line)));
    }

    /** 是否落在页首或页尾带内 */
    private static boolean isInBand(float y, float pageHeight) {
        float band = pageHeight * BAND_RATIO;
        return y <= band || y >= pageHeight - band;
    }

    /** 方向角偏离水平超过容差即视为旋转，斜向水印的主要特征 */
    private static boolean isRotated(TextPosition position) {
        float dir = position.getDir() % 360f;
        if (dir < 0) {
            dir += 360f;
        }
        return dir > ROTATION_TOLERANCE && dir < 360f - ROTATION_TOLERANCE;
    }

    /** 数字归一化：让「第 3 页 共 60 页」与「第 4 页 共 60 页」归为同一个 key */
    private static String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\d+", "#")
                .replaceAll("\\s+", " ");
    }

    /** 取中位数；不用平均值是为了避免被少量大字（水印）拉高基准 */
    private static float median(List<Float> values) {
        if (values.isEmpty()) {
            return 0f;
        }
        List<Float> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    /** 行内的一段文本及其坐标（PDFBox 在文本间隙处会把一行拆成多段） */
    private record Fragment(String text, float x, float y, float fontSize, float pageHeight, boolean horizontal) {
    }

    /** 归一化文本的跨页出现情况 */
    private static class KeyStat {
        /** 出现过该文本的页号 */
        final Set<Integer> pages = new HashSet<>();
        /** 其中落在页首页尾带内的页号 */
        final Set<Integer> bandPages = new HashSet<>();
        /** 各页 y 坐标的最小/最大值，用于判断该文本的位置是否固定 */
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        /** 出现过的最大字号，用于水印的字号判定 */
        float maxFontSize;
    }
}
