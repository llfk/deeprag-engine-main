package com.deeprag.parser;

import io.github.hzkitty.RapidOCR;
import io.github.hzkitty.entity.RecResult;
import org.opencv.core.Point;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 扫描页的文字提取（OCR）
 * <p>
 * 把渲染成图片的 PDF 页面交给 RapidOCR 识别，再按识别框坐标把结果拼回文字行。
 * <p>
 * 为什么必须自己拼行：引擎的 {@code getStrRes()} 是「一个识别框一行」的输出，
 * 表格会被拍成一列（编号一行、姓名一行、部门一行…），完全不可读；
 * 只有按 y 分行、行内按 x 拼接才能还原出正常的文字顺序。
 * <p>
 * 引擎初始化约 3 秒（要加载三个 ONNX 模型），所以整个进程复用一个实例。
 * 若原生库在当前平台加载失败（例如 Alpine 的 musl libc，或不受支持的 CPU 架构），
 * 会标记为不可用并让调用方走降级路径，而不是让整个解析流程崩掉。
 */
public class OcrTextExtractor {

    /** 引擎不可用（原生库缺失或平台不支持）；只探测一次，避免每次调用都白等 */
    private static boolean unavailable;

    /** 进程内复用的引擎实例 */
    private static RapidOCR engine;

    private OcrTextExtractor() {
    }

    /** 取引擎；初始化失败则返回 null 并永久标记不可用 */
    private static synchronized RapidOCR engine() {
        if (unavailable) {
            return null;
        }
        if (engine == null) {
            try {
                engine = RapidOCR.create();
            } catch (Throwable t) {
                // UnsatisfiedLinkError / NoClassDefFoundError 都是 Error 而不是 Exception，
                // 必须捕获 Throwable，否则平台不支持时整个解析流程直接崩
                unavailable = true;
                return null;
            }
        }
        return engine;
    }

    /** OCR 是否可用（原生库能加载、模型能初始化） */
    public static synchronized boolean available() {
        return engine() != null;
    }

    /**
     * 识别一页图片，返回按阅读顺序拼好的文字
     *
     * @param image 已渲染的页面图片
     * @return 逐行的文字；同一行内的各识别框以空格分隔
     */
    public static synchronized String extract(BufferedImage image) {
        RapidOCR ocr = engine();
        if (ocr == null) {
            throw new IllegalStateException(
                    "OCR 引擎不可用：RapidOCR 原生库无法在当前平台加载（需要 glibc 系系统与受支持的 CPU 架构）");
        }
        try {
            return toLines(ocr.run(image).getRecRes());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OCR 识别失败", e);
        }
    }

    /** 一个识别框：文本 + 轴对齐包围盒（单位是渲染图片的像素） */
    private record Box(String text, float left, float right, float top, float bottom) {
    }

    /**
     * 把识别框拼回文字行
     * <p>
     * 行容差取「字高中位数 × 0.6」：同一行内各框的上边缘会有几个像素的抖动，
     * 而行间距一般是字高的两倍左右——取中位字高的六成，既吸收抖动又不会把相邻两行并起来。
     */
    static String toLines(List<RecResult> results) {
        List<Box> boxes = new ArrayList<>();
        for (RecResult result : results) {
            Point[] points = result.getDtBoxes();
            if (points == null || points.length == 0) {
                continue;
            }
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (Point p : points) {
                minX = Math.min(minX, (float) p.x);
                maxX = Math.max(maxX, (float) p.x);
                minY = Math.min(minY, (float) p.y);
                maxY = Math.max(maxY, (float) p.y);
            }
            boxes.add(new Box(result.getText(), minX, maxX, minY, maxY));
        }
        if (boxes.isEmpty()) {
            return "";
        }

        List<Float> heights = new ArrayList<>();
        for (Box box : boxes) {
            heights.add(box.bottom() - box.top());
        }
        heights.sort(Float::compare);
        float tolerance = heights.get(heights.size() / 2) * 0.6f;

        boxes.sort(Comparator.comparingDouble(Box::top).thenComparingDouble(Box::left));
        List<List<Box>> rows = new ArrayList<>();
        for (Box box : boxes) {
            List<Box> last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (last != null && Math.abs(last.get(0).top() - box.top()) <= tolerance) {
                last.add(box);
            } else {
                List<Box> row = new ArrayList<>();
                row.add(box);
                rows.add(row);
            }
        }

        StringBuilder text = new StringBuilder();
        for (List<Box> row : rows) {
            row.sort(Comparator.comparingDouble(Box::left));
            StringBuilder line = new StringBuilder();
            for (Box box : row) {
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(box.text());
            }
            text.append(line).append('\n');
        }
        return text.toString().strip();
    }
}
