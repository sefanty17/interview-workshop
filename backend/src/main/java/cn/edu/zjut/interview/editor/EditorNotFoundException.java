package cn.edu.zjut.interview.editor;

/** 剧本编辑器：目标行（剧本 / 章节）不存在，controller 转 404。 */
public class EditorNotFoundException extends RuntimeException {

    public EditorNotFoundException(String message) {
        super(message);
    }
}
