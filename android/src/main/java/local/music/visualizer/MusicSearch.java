// SPDX-FileCopyrightText: 2026 Himanshu_Sharma (HimanshuS073)
// SPDX-License-Identifier: GPL-3.0-or-later

package local.music.visualizer;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

// Android uses a Java extractor instead of launching desktop Python and mpv.
final class MusicSearch {
    static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).build();
    static final class Song {
        final String title, artist, url, thumbnail;
        final long duration;
        Song(StreamInfoItem item) {
            title=item.getName(); artist=item.getUploaderName(); url=item.getUrl();
            duration=item.getDuration();
            thumbnail=item.getThumbnails().isEmpty()?"":item.getThumbnails().get(0).getUrl();
        }
    }
    static final class Stream {
        final String audio, video;
        Stream(String audio, String video) { this.audio=audio; this.video=video; }
    }
    static {
        NewPipe.init(new Downloader() {
            @Override public Response execute(Request request) throws IOException {
                byte[] data=request.dataToSend();
                okhttp3.Request.Builder builder=new okhttp3.Request.Builder().url(request.url())
                    .header("User-Agent", "Mozilla/5.0")
                    .method(request.httpMethod(),data==null?null:RequestBody.create(null,data));
                for(Map.Entry<String,List<String>> header:request.headers().entrySet()) {
                    builder.removeHeader(header.getKey());
                    for(String value:header.getValue()) builder.addHeader(header.getKey(),value);
                }
                try(okhttp3.Response result=HTTP.newCall(builder.build()).execute()) {
                    String body=result.body()==null?"":result.body().string();
                    return new Response(result.code(),result.message(),result.headers().toMultimap(),
                                        body,result.request().url().toString());
                }
            }
        });
    }
    static List<Song> search(String name) throws Exception {
        SearchExtractor extractor=ServiceList.YouTube.getSearchExtractor(name);
        extractor.fetchPage();
        List<Song> songs=new ArrayList<>();
        for(InfoItem item:extractor.getInitialPage().getItems()) {
            if(item instanceof StreamInfoItem) songs.add(new Song((StreamInfoItem)item));
            if(songs.size()==20) break;
        }
        return songs;
    }
    static Stream resolve(Song song) throws Exception {
        StreamInfo info=StreamInfo.getInfo(ServiceList.YouTube,song.url);
        VideoStream combined=null,video=null;
        for(VideoStream candidate:info.getVideoStreams()) {
            if(candidate.isUrl() && (combined==null || height(candidate)<height(combined))) combined=candidate;
        }
        if(combined!=null) return new Stream(combined.getContent(),"");
        for(VideoStream candidate:info.getVideoOnlyStreams()) {
            if(candidate.isUrl() && (video==null || height(candidate)<height(video))) video=candidate;
        }
        AudioStream audio=null;
        for(AudioStream candidate:info.getAudioStreams()) {
            if(candidate.isUrl() && (audio==null || candidate.getAverageBitrate()>audio.getAverageBitrate())) audio=candidate;
        }
        if(audio==null) throw new IOException("No playable stream. Try another result.");
        return new Stream(audio.getContent(),video==null?"":video.getContent());
    }
    private static int height(VideoStream stream) {
        try { return Integer.parseInt(stream.getResolution().replaceAll("[^0-9]", "")); }
        catch(Exception ignored) { return 10000; }
    }
}
