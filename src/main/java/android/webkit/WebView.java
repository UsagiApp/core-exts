package android.webkit;

import android.content.Context;

public class WebView {
    public WebView(Context context) {}

    public WebSettings getSettings() {
        return new WebSettings();
    }

    public void setWebViewClient(WebViewClient client) {}
    public WebViewClient getWebViewClient() { return null; }

    public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
        if (resultCallback != null) {
            resultCallback.onReceiveValue("null");
        }
    }

    public void loadDataWithBaseURL(String baseUrl, String data, String mimeType, String encoding, String historyUrl) {}

    public void destroy() {}
}
