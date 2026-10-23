package cn.edu.zjut.interview.dto;

import cn.edu.zjut.interview.editor.ChapterEdge;
import cn.edu.zjut.interview.editor.Diagnostic;

import java.util.List;

/**
 * 校验报告，对应原版 {@code validate.rs:112-123} 的 {@code ValidationReport}。
 *
 * @param diagnostics 诊断列表，已按 severity → chapter → eventIndex 排序
 * @param errorCount  error 条数
 * @param warnCount   warn 条数
 * @param infoCount   info 条数
 * @param variables   收集到的全部变量名，供编辑器做变量面板
 * @param edges       章节跳转边，供前端画真实的流程图连线与判断能否拖拽重排
 */
public record ValidationReportView(
        List<Diagnostic> diagnostics,
        int errorCount,
        int warnCount,
        int infoCount,
        List<String> variables,
        List<ChapterEdge> edges) {
}
