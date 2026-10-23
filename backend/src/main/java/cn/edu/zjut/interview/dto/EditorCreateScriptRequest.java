package cn.edu.zjut.interview.dto;

/**
 * 新建剧本（规格 E5a §1.1）。
 *
 * <p>原版新建剧本只是「在磁盘上 mkdir + 写一份最简 story_config.yaml」，字段只有
 * 目录名 / 简介 / 是否羁绊冒险。本项目剧本存在 {@code interview_script} 一行里，
 * 没有「羁绊冒险」，于是把原版的开关换成项目的面试参数（岗位 / 面试官 / 难度 / 公司风格）。
 *
 * <p>{@code code} 是剧本唯一标识（等于原版的目录名），{@code introChapter} 指开场章节 id。
 */
public record EditorCreateScriptRequest(
        String code,
        String scriptName,
        String description,
        String jobCode,
        String level,
        String companyStyle,
        String interviewerCode,
        String introChapter) {
}
