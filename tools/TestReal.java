import com.dsh.dydl.DouyinParser;
import java.nio.file.*;
import java.util.List;
public class TestReal {
    public static void main(String[] a) throws Exception {
        String html = new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8");
        System.out.println("html len " + html.length());
        System.out.println("has images:[ " + html.contains("\"images\":["));
        List<DouyinParser.MediaItem> l = DouyinParser.imagesFromHtml(html);
        System.out.println("items=" + l.size());
        int i = 0;
        for (DouyinParser.MediaItem m : l) {
            if (++i > 6) break;
            System.out.println(" " + m.kind + " " + m.url.substring(0, Math.min(90, m.url.length())));
        }
        String js = new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8");
        System.out.println("json images: " + js.contains("\"images\":[") + " len " + js.length());
        try {
            List<DouyinParser.MediaItem> j = DouyinParser.itemsFromJson(js, a.length > 2 ? a[2] : null);
            System.out.println("json items=" + j.size());
        } catch (Exception e) {
            System.out.println("json err " + e);
        }
    }
}
