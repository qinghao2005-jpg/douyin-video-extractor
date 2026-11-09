import com.dsh.dydl.DouyinParser;
import java.util.List;
public class TestHtml {
    public static void main(String[] a) {
        String html = "<script>var d={\"images\":[{\"url_list\":[\"https:\\/\\/p3-sign.douyinpic.com\\/x.jpeg?x=1\",\"https:\\/\\/p3-sign.douyinpic.com\\/x.jpeg?x=1\"]},"
                + "{\"url_list\":[\"https:\\/\\/p6-sign.douyinpic.com\\/y.jpeg\"],\"video\":{\"play_addr\":{\"url_list\":[\"https:\\/\\/aweme.snssdk.com\\/aweme\\/v1\\/playwm\\/?video_id=v0300abc\"]}}},"
                + "{\"url_list\":[\"https:\\/\\/aweme-avatar\\/a.jpeg\"]}]}</script>";
        List<DouyinParser.MediaItem> l = DouyinParser.itemsFromHtml(html);
        System.out.println("count=" + l.size());
        for (DouyinParser.MediaItem m : l) System.out.println(m.kind + " " + m.url);
    }
}
