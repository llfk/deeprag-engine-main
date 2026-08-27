package com.deeprag.log;

/**
 * ANSI 彩色控制台日志工具
 * <p>
 * 提供分级日志输出（INFO/WARN/ERROR/STEP/DIM/HEADER），
 * 使用 ANSI 转义序列实现彩色终端输出，方便在开发调试时区分不同级别的信息。
 * 所有方法为静态方法，全局可直接调用。
 */
public class ConsoleLog {

    private static final String RESET = "[0m";
    private static final String GREEN = "[32m";
    private static final String YELLOW = "[33m";
    private static final String RED = "[31m";
    private static final String CYAN = "[36m";
    private static final String GRAY = "[90m";

    public static void info(String msg) {
        System.out.println(GREEN + "[INFO]" + RESET + " " + msg);
    }

    public static void warn(String msg) {
        System.out.println(YELLOW + "[WARN]" + RESET + " " + msg);
    }

    public static void error(String msg) {
        System.out.println(RED + "[ERROR]" + RESET + " " + msg);
    }

    public static void step(String msg) {
        System.out.println(CYAN + "  →" + RESET + " " + msg);
    }

    public static void dim(String msg) {
        System.out.println(GRAY + "  " + msg + RESET);
    }

    public static void blank() {
        System.out.println();
    }

    public static void header(String title) {
        System.out.println();
        System.out.println(CYAN + "╔════════════════════════════════════════╗" + RESET);
        System.out.println(CYAN + "║" + RESET + "     " + title + "          " + CYAN + "║" + RESET);
        System.out.println(CYAN + "╚════════════════════════════════════════╝" + RESET);
        System.out.println();
    }
}
