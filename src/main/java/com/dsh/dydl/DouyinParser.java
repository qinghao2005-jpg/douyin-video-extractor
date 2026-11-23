package com.dsh.dydl;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** 从抖音分享链接/分享文案里取出无水印视频地址。只用公开分享页，不做签名伪造。 */
public final class DouyinParser {

    public static class Video {
        public String title = "抖音视频";
        public String author = "";
        public String url = "";
        public String cover = "";
    }

    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    private DouyinParser() {
    }

    public static String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String u = m.group();
            while (u.length() > 0 && ".,;，。、）)】]".indexOf(u.charAt(u.length() - 1)) >= 0) {
                u = u.substring(0, u.length() - 1);
            }
            if (u.contains("douyin.com")) return u;
        }
        m = URL_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }

    public static Video parse(String raw, String ua) throws Exception {
        String link = extractUrl(raw);
        if (link == null) throw new IOException("没找到链接，先复制抖音的分享口令");

        String finalUrl = link;
        String html = null;
        for (int i = 0; i < 5; i++) {
            Resp r = fetch(finalUrl, ua);
            if (r.location != null) {
                finalUrl = absolutize(finalUrl, r.location);
                continue;
            }
            html = r.body;
            finalUrl = r.url;
            break;
        }
        if (html == null || html.isEmpty()) throw new IOException("页面是空的，可能被风控拦了");

        String low = html.toLowerCase();
        if (low.contains("验证") && low.contains("captcha") && html.length() < 4000) {
            throw new IOException("被抖音风控拦住，稍后再试或换个链接");
        }

        Object payload = findPayload(html);
        Video v = new Video();
        String playUrl = payload == null ? null : searchPlay(payload, 0);
        if (playUrl == null) playUrl = regexPlay(html);
        if (playUrl == null) throw new IOException("页面结构变了，没解析出视频地址");
        if (!playUrl.startsWith("http")) playUrl = "https:" + playUrl;
        v.url = dewatermark(playUrl);

        String title = payload == null ? null : searchString(payload, "desc", 0);
        if (title == null) title = metaContent(html, "og:title");
        if (title == null) title = htmlTitle(html);
        if (title != null && title.trim().length() > 0) v.title = title.trim();

        String author = payload == null ? null : searchString(payload, "nickname", 0);
        if (author != null) v.author = author;

        String cover = payload == null ? null : searchCover(payload, 0);
        if (cover != null) v.cover = cover.startsWith("http") ? cover : "https:" + cover;
        return v;
    }

    /**
     * 只把带水印的 playwm 换成 play，不再用 video_id 重新拼 iesdouyin 接口。
     * 老写法会丢掉地址里的 sign/file_id 等签名参数，服务端会回 200 空包。
     */
    public static String dewatermark(String u) {
        if (u == null) return "";
        return u.replace("playwm", "play");
    }


    /** 直接解析接口返回的 JSON 文本（WebView 里拦截到的详情数据）。 */
    public static Video fromJson(String json) throws Exception {
        if (json == null || json.length() < 20) throw new IOException("没有拿到数据");
        Video v = new Video();
        Object payload;
        try {
            payload = new JSONObject(json);
        } catch (Exception e) {
            int i = json.indexOf('{');
            if (i < 0) throw new IOException("数据格式不对");
            payload = new JSONObject(json.substring(i));
        }
        String playUrl = searchPlay(payload, 0);
        if (playUrl == null) throw new IOException("数据里没有视频地址");
        if (!playUrl.startsWith("http")) playUrl = "https:" + playUrl;
        v.url = dewatermark(playUrl);
        String t = searchString(payload, "desc", 0);
        if (t != null && t.trim().length() > 0) v.title = t.trim();
        String a = searchString(payload, "nickname", 0);
        if (a != null) v.author = a;
        String c = searchCover(payload, 0);
        if (c != null) v.cover = c.startsWith("http") ? c : "https:" + c;
        return v;
    }

    /** 从 HTML 里兜底找地址（页面内嵌数据时用）。 */
    public static Video fromHtml(String html) throws Exception {
        if (html == null) throw new IOException("页面是空的");
        Object payload = findPayload(html);
        if (payload == null) throw new IOException("页面里没有内嵌数据");
        return fromJson(payload instanceof JSONObject ? payload.toString() : String.valueOf(payload));
    }


    /** 一条作品里的一个素材：普通视频 / 图片 / 动图 */
    public static class MediaItem {
        public static final int VIDEO = 0;
        public static final int IMAGE = 1;
        public static final int ANIM = 2;
        public int kind = VIDEO;
        public String url = "";
        public String thumb = "";
    }

    /** 挑目标作品：有 id 只认 id，没 id 就取第一份详情 */
    private static JSONObject pickAweme(Object node, String targetId) {
        if (targetId != null) return findByAwemeId(node, targetId, 0);
        return firstDetail(node, 0);
    }

    private static JSONObject findByAwemeId(Object node, String id, int depth) {
        if (node == null || depth > 14) return null;
        try {
            if (node instanceof JSONObject) {
                JSONObject j = (JSONObject) node;
                if (id.equals(j.optString("aweme_id"))) return j;
                for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                    JSONObject r = findByAwemeId(j.opt(it.next()), id, depth + 1);
                    if (r != null) return r;
                }
            } else if (node instanceof JSONArray) {
                JSONArray a = (JSONArray) node;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject r = findByAwemeId(a.opt(i), id, depth + 1);
                    if (r != null) return r;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static JSONObject firstDetail(Object node, int depth) {
        if (node == null || depth > 10) return null;
        try {
            if (node instanceof JSONObject) {
                JSONObject j = (JSONObject) node;
                for (String key : new String[]{"aweme_detail", "aweme_details", "aweme_list"}) {
                    Object v = j.opt(key);
                    if (v instanceof JSONArray && ((JSONArray) v).length() > 0) {
                        JSONObject o = ((JSONArray) v).optJSONObject(0);
                        if (o != null) return o;
                    } else if (v instanceof JSONObject) {
                        return (JSONObject) v;
                    }
                }
                for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                    JSONObject r = firstDetail(j.opt(it.next()), depth + 1);
                    if (r != null) return r;
                }
            } else if (node instanceof JSONArray) {
                JSONArray a = (JSONArray) node;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject r = firstDetail(a.opt(i), depth + 1);
                    if (r != null) return r;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 从分享链接里取作品 id */
    public static String idOf(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("/(?:share/)?(?:video|note|slides|image)/(\\d{8,})").matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("[?&](?:modal_id|aweme_id|item_id)=(\\d{8,})").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    private static boolean isMusic(String u) {
        if (u == null) return true;
        String low = u.toLowerCase();
        return low.contains("ies-music") || low.endsWith(".mp3") || low.contains("/obj/ies-music");
    }

    /** 解析一条作品的素材列表（图文作品会有多个） */
    public static java.util.List<MediaItem> itemsFromJson(String json) throws Exception {
        return itemsFromJson(json, null);
    }

    /** 指定 id 时只在 aweme_list 里挑那一条，避免拿到作者的其他作品 */
    public static java.util.List<MediaItem> itemsFromJson(String json, String targetId) throws Exception {
        java.util.List<MediaItem> out = new java.util.ArrayList<MediaItem>();
        if (json == null || json.length() < 20) throw new IOException("没有拿到数据");
        Object payload;
        try {
            payload = new JSONObject(json);
        } catch (Exception e) {
            int i = json.indexOf('{');
            if (i < 0) throw new IOException("数据格式不对");
            payload = new JSONObject(json.substring(i));
        }
        JSONObject target = pickAweme(payload, targetId);
        if (targetId != null && target == null
                && (json.contains("\"aweme_list\"") || json.contains("\"aweme_details\""))) {
            return out; // 拿到了别的作品，别将就
        }
        if (target != null) payload = target;
        JSONArray imgs = findImages(payload, 0);
        if (imgs != null && imgs.length() > 0) {
            for (int i = 0; i < imgs.length(); i++) {
                JSONObject o = imgs.optJSONObject(i);
                if (o == null) continue;
                MediaItem m = new MediaItem();
                m.thumb = firstOf(o.optJSONArray("url_list"));
                JSONObject v = o.optJSONObject("video");
                String play = v == null ? null : searchPlay(v, 0);
                if (play != null) {
                    m.kind = MediaItem.ANIM;
                    m.url = dewatermark(play);
                    String c = searchCover(v, 0);
                    if (c != null) m.thumb = c.startsWith("http") ? c : "https:" + c;
                } else if (m.thumb != null) {
                    m.kind = MediaItem.IMAGE;
                    m.url = m.thumb;
                }
                if (!m.url.isEmpty()) out.add(m);
            }
        }
        if (out.isEmpty()) {
            String play = searchPlay(payload, 0);
            if (isMusic(play)) play = null;
            if (play != null) {
                MediaItem m = new MediaItem();
                m.kind = MediaItem.VIDEO;
                m.url = dewatermark(play);
                m.thumb = searchCover(payload, 0);
                out.add(m);
            }
        }
        return out;
    }

    /** 从 JSON 里取标题 */
    public static String titleOf(String json) {
        try {
            Object payload = new JSONObject(json);
            String t = searchString(payload, "desc", 0);
            if (t != null && t.trim().length() > 0) return t.trim();
        } catch (Exception ignored) {
        }
        return "抖音作品";
    }



    /** 只从 HTML 里的 images 数组取素材，避免把封面/头像/推荐也扫进来 */
    public static java.util.List<MediaItem> imagesFromHtml(String html) {
        java.util.List<MediaItem> out = new java.util.ArrayList<MediaItem>();
        if (html == null) return out;
        int idx = html.indexOf("\"images\":[");
        if (idx < 0) {
            idx = html.indexOf("\\\"images\\\":[");
        }
        if (idx < 0) return out;
        int start = html.indexOf('[', idx);
        int end = matchBracket(html, start);
        if (start < 0 || end < 0 || end <= start) return out;
        String block = html.substring(start, end + 1);

        Matcher m = Pattern.compile("\\\"url_list\\\"\\s*:\\s*\\[\\s*\\\"([^\"]+)\\\"")
                .matcher(block);
        MediaItem cur = null;
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        while (m.find() && out.size() < 60) {
            String u = m.group(1).replace("\\u002F", "/").replace("\\/", "/");
            if (!u.startsWith("http")) continue;
            String low = u.toLowerCase();
            boolean video = low.contains("douyinvod") || low.contains("/play/") || low.contains("playwm")
                    || low.endsWith(".mp4");
            if (video) {
                if (cur != null && cur.kind == MediaItem.IMAGE) {
                    cur.kind = MediaItem.ANIM;
                    cur.url = dewatermark(u);
                }
                continue;
            }
            if (!(low.contains("douyinpic") || low.contains("byteimg") || low.contains(".jpeg")
                    || low.contains(".webp") || low.contains("tplv"))) {
                continue;
            }
            if (!seen.add(u)) continue;
            MediaItem it = new MediaItem();
            it.kind = MediaItem.IMAGE;
            it.url = u;
            it.thumb = u;
            out.add(it);
            cur = it;
        }
        return out;
    }

    private static int matchBracket(String s, int start) {
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** 从 HTML 里取标题 */
    public static String titleFromHtml(String html) {
        try {
            Matcher m = Pattern.compile("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]*)\"")
                    .matcher(html);
            if (m.find() && m.group(1).trim().length() > 0) return m.group(1).trim();
            Matcher t2 = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL).matcher(html);
            if (t2.find() && t2.group(1).trim().length() > 0) {
                String v = t2.group(1).trim();
                return v.replaceAll("\\s*-\\s*抖音$", "");
            }
        } catch (Exception ignored) {
        }
        return "抖音作品";
    }

    /** 从页面 HTML 里扫 url_list，兜底找出图集/视频地址 */
    public static java.util.List<MediaItem> itemsFromHtml(String html) {
        java.util.List<MediaItem> out = new java.util.ArrayList<MediaItem>();
        if (html == null || html.length() < 50) return out;
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        try {
            Matcher m = Pattern.compile("\\\"url_list\\\"\\s*:\\s*\\[\\s*\\\"([^\"]+)\\\"")
                    .matcher(html);
            while (m.find() && out.size() < 40) {
                String u = m.group(1).replace("\\u002F", "/").replace("\\/", "/");
                if (!u.startsWith("http")) continue;
                if (!seen.add(u)) continue;
                String low = u.toLowerCase();
                if (low.contains("avatar") || low.contains("100x100")) continue;
                MediaItem it = new MediaItem();
                if (low.contains("douyinvod") || low.contains("/play/") || low.contains("playwm")
                        || low.endsWith(".mp4")) {
                    it.kind = MediaItem.VIDEO;
                    it.url = dewatermark(u);
                } else if (low.contains("douyinpic") || low.contains("byteimg")
                        || low.contains(".jpeg") || low.contains(".webp") || low.contains("tplv")) {
                    it.kind = MediaItem.IMAGE;
                    it.url = u;
                    it.thumb = u;
                } else {
                    continue;
                }
                out.add(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 找 images 数组（元素里带 url_list 的那种） */
    private static JSONArray findImages(Object node, int depth) {
        if (node == null || depth > 16) return null;
        if (node instanceof JSONObject) {
            JSONObject j = (JSONObject) node;
            JSONArray a = j.optJSONArray("images");
            if (a != null && a.length() > 0 && a.optJSONObject(0) != null
                    && a.optJSONObject(0).optJSONArray("url_list") != null) {
                return a;
            }
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                Object v = j.opt(it.next());
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    JSONArray r = findImages(v, depth + 1);
                    if (r != null) return r;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONArray r = findImages(a.opt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ---------- 页面里的 JSON 载荷 ----------

    private static Object findPayload(String html) {
        try {
            Matcher m = Pattern.compile(
                    "<script[^>]*id=\"RENDER_DATA\"[^>]*>(.*?)</script>", Pattern.DOTALL).matcher(html);
            if (m.find()) {
                String json = URLDecoder.decode(m.group(1), "UTF-8");
                return new JSONObject(json);
            }
        } catch (Exception ignored) {
        }
        try {
            int idx = html.indexOf("_ROUTER_DATA");
            if (idx >= 0) {
                int start = html.indexOf('{', idx);
                if (start > 0) {
                    int end = matchBrace(html, start);
                    if (end > start) return new JSONObject(html.substring(start, end + 1));
                }
            }
        } catch (Exception ignored) {
        }
        try {
            int idx = html.indexOf("__pace_f");
            if (idx >= 0) {
                int start = html.indexOf('{', idx);
                if (start > 0) {
                    int end = matchBrace(html, start);
                    if (end > start) return new JSONObject(html.substring(start, end + 1));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int matchBrace(String s, int start) {
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String searchPlay(Object node, int depth) {
        if (node == null || depth > 16) return null;
        if (node instanceof JSONObject) {
            JSONObject j = (JSONObject) node;
            JSONObject pa = j.optJSONObject("play_addr");
            if (pa != null) {
                String u = firstOf(pa.optJSONArray("url_list"));
                if (u != null) return u;
                String uri = pa.optString("uri", null);
                if (uri != null && uri.length() > 10) {
                    return "https://aweme.snssdk.com/aweme/v1/play/?video_id=" + uri + "&ratio=1080p&line=0";
                }
            }
            JSONArray ul = j.optJSONArray("url_list");
            if (ul != null) {
                String u = firstOf(ul);
                if (u != null && (u.contains("/play") || u.contains(".mp4"))) return u;
            }
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = j.opt(k);
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    String r = searchPlay(v, depth + 1);
                    if (r != null) return r;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String r = searchPlay(a.opt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String searchString(Object node, String key, int depth) {
        if (node == null || depth > 16) return null;
        if (node instanceof JSONObject) {
            JSONObject j = (JSONObject) node;
            String s = j.optString(key, null);
            if (s != null && s.trim().length() > 0) return s;
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                Object v = j.opt(it.next());
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    String r = searchString(v, key, depth + 1);
                    if (r != null) return r;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String r = searchString(a.opt(i), key, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String searchCover(Object node, int depth) {
        if (node == null || depth > 16) return null;
        if (node instanceof JSONObject) {
            JSONObject j = (JSONObject) node;
            for (String key : new String[]{"origin_cover", "cover", "dynamic_cover"}) {
                JSONObject c = j.optJSONObject(key);
                if (c != null) {
                    String u = firstOf(c.optJSONArray("url_list"));
                    if (u != null) return u;
                }
            }
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                Object v = j.opt(it.next());
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    String r = searchCover(v, depth + 1);
                    if (r != null) return r;
                }
            }
        }
        return null;
    }

    private static String firstOf(JSONArray a) {
        if (a == null || a.length() == 0) return null;
        String s = a.optString(0, null);
        return s == null || s.length() < 8 ? null : s;
    }

    private static String regexPlay(String html) {
        Pattern p = Pattern.compile(
                "\"play_addr\"\\s*:\\s*\\{[^}]*?\"url_list\"\\s*:\\s*\\[\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(html);
        if (m.find()) return m.group(1).replace("\\u002F", "/").replace("\\/", "/");
        p = Pattern.compile("\"url_list\"\\s*:\\s*\\[\\s*\"(https:[^\"]*(?:play|\\.mp4)[^\"]*)\"");
        m = p.matcher(html);
        if (m.find()) return m.group(1).replace("\\u002F", "/").replace("\\/", "/");
        return null;
    }

    private static String metaContent(String html, String prop) {
        Matcher m = Pattern.compile("<meta[^>]+(?:property|name)=\"" + prop
                + "\"[^>]+content=\"([^\"]*)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String htmlTitle(String html) {
        Matcher m = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL).matcher(html);
        return m.find() ? m.group(1) : null;
    }

    // ---------- HTTP ----------

    private static class Resp {
        String body;
        String url;
        String location;
    }

    private static Resp fetch(String url, String ua) throws IOException {
        Resp r = new Resp();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        c.setRequestProperty("Accept-Encoding", "identity");
        int code = c.getResponseCode();
        r.url = c.getURL().toString();
        if (code >= 300 && code < 400) {
            r.location = c.getHeaderField("Location");
            c.disconnect();
            if (r.location == null) throw new IOException("跳转失败 HTTP " + code);
            return r;
        }
        if (code != 200) throw new IOException("HTTP " + code);
        String enc = c.getContentEncoding();
        InputStream in = c.getInputStream();
        if (enc != null && enc.toLowerCase().contains("gzip")) in = new GZIPInputStream(in);
        else if (enc != null && enc.toLowerCase().contains("deflate")) in = new InflaterInputStream(in);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        c.disconnect();
        r.body = new String(bos.toByteArray(), "UTF-8");
        return r;
    }

    private static String absolutize(String base, String loc) {
        try {
            return new URI(base).resolve(loc).toString();
        } catch (Exception e) {
            return loc;
        }
    }
}
