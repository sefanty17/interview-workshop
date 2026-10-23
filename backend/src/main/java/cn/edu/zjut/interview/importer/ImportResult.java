package cn.edu.zjut.interview.importer;

/**
 * 一次导入的返回值（规格 B5-BE §2.6）。
 *
 * <p>{@code created == false} 表示同一个 {@code scriptCode} 已存在、本次是重建章节；
 * 幂等性靠它自证，不靠调用方猜。
 */
public record ImportResult(
        String scriptCode,
        String scriptName,
        boolean created,
        long scriptId,
        ImportReport report) {
}
