package cn.edu.zjut.interview.editor;

/** 角色卡目录不存在（规格 R1a §4），controller 转 404。 */
public class CharacterNotFoundException extends RuntimeException {

    public CharacterNotFoundException(String message) {
        super(message);
    }
}
