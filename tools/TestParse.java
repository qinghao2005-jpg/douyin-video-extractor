import com.dsh.dydl.DouyinParser;

/** 桌面端自测：java -cp tools/json.jar:build/classes:build/test TestParse "<分享链接>" */
public class TestParse {
    public static void main(String[] a) throws Exception {
        if (a.length == 0) {
            System.out.println("用法: TestParse <链接或分享文案>");
            return;
        }
        String ua = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
        DouyinParser.Video v = DouyinParser.parse(a[0], ua);
        System.out.println("title : " + v.title);
        System.out.println("author: " + v.author);
        System.out.println("cover : " + v.cover);
        System.out.println("url   : " + v.url);
    }
}
