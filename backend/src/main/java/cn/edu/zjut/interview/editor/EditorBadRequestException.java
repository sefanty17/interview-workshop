package cn.edu.zjut.interview.editor;

/** 剧本编辑器：请求内容不合法，controller 转 400。 */
public class EditorBadRequestException extends RuntimeException {

    public EditorBadRequestException(String message) {
        super(message);
    }
}
