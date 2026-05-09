package com.github.catvod.spider;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 弹幕配置实体类
 */
public class DanmakuConfig {
    /**
     * 弹幕API地址
     */
    public Set<String> apiUrls;
    /**
     * 弹幕搜索框宽度比例
     */
    public float lpWidth;
    /**
     * 弹幕搜索框高度比例
     */
    public float lpHeight;
    /**
     * 弹幕搜索框透明度
     */
    public float lpAlpha;
    /**
     * 自动推送弹幕开关
     */
    public boolean autoPushEnabled;
    /**
     * 弹幕搜索框样式
     */
    public String danmakuStyle;
    /**
     * 静默模式，开启时不再弹出任何提示信息
     */
    public boolean silentMode;
    /**
     * 弹幕时间偏移，单位毫秒。正数延后，负数提前。
     */
    public int danmakuTimeOffsetMs;

    public DanmakuConfig() {
        // 设置默认值
        apiUrls = new HashSet<>();
        lpWidth = 0.9f;
        lpHeight = 0.85f;
        lpAlpha = 0.9f;
        autoPushEnabled = false;
        danmakuStyle = "模板一";
        silentMode = true;
        danmakuTimeOffsetMs = 0;
    }

    public void updateFromJson(JSONObject json) {
        if (json == null) return;
        if (json.has("apiUrl")) {
            apiUrls.addAll(Arrays.asList(json.optString("apiUrl").split(",")));
        }
        if (json.has("lpWidth")) {
            setLpWidth((float) json.optDouble("lpWidth", lpWidth));
        }
        if (json.has("lpHeight")) {
            setLpHeight((float) json.optDouble("lpHeight", lpHeight));
        }
        if (json.has("lpAlpha")) {
            setLpAlpha((float) json.optDouble("lpAlpha", lpAlpha));
        }
        if (json.has("autoPushEnabled")) {
            setAutoPushEnabled(json.optBoolean("autoPushEnabled", autoPushEnabled));
        }
        if (json.has("danmakuStyle")) {
            setDanmakuStyle(json.optString("danmakuStyle", danmakuStyle));
        }
        if (json.has("silentMode")) {
            setSilentMode(json.optBoolean("silentMode", silentMode));
        }
        if (json.has("danmakuTimeOffsetMs")) {
            setDanmakuTimeOffsetMs(json.optInt("danmakuTimeOffsetMs", danmakuTimeOffsetMs));
        } else if (json.has("danmakuOffsetMs")) {
            setDanmakuTimeOffsetMs(json.optInt("danmakuOffsetMs", danmakuTimeOffsetMs));
        } else if (json.has("danmakuOffset")) {
            setDanmakuTimeOffsetMs((int) Math.round(json.optDouble("danmakuOffset", danmakuTimeOffsetMs / 1000.0) * 1000));
        }
    }

    public Set<String> getApiUrls() {
        return apiUrls;
    }

    public void setApiUrls(Set<String> apiUrls) {
        this.apiUrls = apiUrls;
    }

    public float getLpWidth() {
        return lpWidth;
    }

    public void setLpWidth(float lpWidth) {
        this.lpWidth = lpWidth;
    }

    public float getLpHeight() {
        return lpHeight;
    }

    public void setLpHeight(float lpHeight) {
        this.lpHeight = lpHeight;
    }

    public float getLpAlpha() {
        return lpAlpha;
    }

    public void setLpAlpha(float lpAlpha) {
        this.lpAlpha = lpAlpha;
    }

    public boolean isAutoPushEnabled() {
        return autoPushEnabled;
    }

    public void setAutoPushEnabled(boolean autoPushEnabled) {
        this.autoPushEnabled = autoPushEnabled;
    }

    public String getDanmakuStyle() {
        return danmakuStyle;
    }

    public void setDanmakuStyle(String danmakuStyle) {
        this.danmakuStyle = danmakuStyle;
    }

    public boolean isSilentMode() {
        return silentMode;
    }

    public void setSilentMode(boolean silentMode) {
        this.silentMode = silentMode;
    }

    public int getDanmakuTimeOffsetMs() {
        if (danmakuTimeOffsetMs > 600000) return 600000;
        if (danmakuTimeOffsetMs < -600000) return -600000;
        return danmakuTimeOffsetMs;
    }

    public void setDanmakuTimeOffsetMs(int danmakuTimeOffsetMs) {
        if (danmakuTimeOffsetMs > 600000) danmakuTimeOffsetMs = 600000;
        if (danmakuTimeOffsetMs < -600000) danmakuTimeOffsetMs = -600000;
        this.danmakuTimeOffsetMs = danmakuTimeOffsetMs;
    }
}
