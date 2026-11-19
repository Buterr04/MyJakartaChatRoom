package site.buterr.chatroom.store;

import java.util.List;

/** Built-in sensitive words seeded only when the table is first created. */
public final class SensitiveWords {

    public static final List<String> DEFAULTS = List.of(
            // 中文脏话/侮辱
            "操你妈", "草泥马", "日你妈", "干你娘", "狗娘养的", "狗东西", "王八蛋",
            "去死", "傻逼", "傻b", "沙比", "煞笔", "笨蛋", "智障", "弱智", "脑残", "废物",
            "贱人", "婊子", "妓女", "嫖娼", "畜生", "混蛋", "你妈的", "他妈的",
            "尼玛", "卧槽", "我操",
            // 英文脏话
            "motherfucker", "fucking", "fucker", "fuck", "shit", "bitch", "asshole",
            "bastard", "cunt", "dick", "pussy", "slut", "whore", "retard");

    private SensitiveWords() {
    }
}
