package jenkins.plugins.instana;

import net.sf.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;

import hudson.AbortException;
import hudson.CloseProofOutputStream;
import hudson.model.TaskListener;
import hudson.remoting.RemoteOutputStream;
import jenkins.security.MasterToSlaveCallable;

import jenkins.plugins.instana.util.HttpClientUtil;
import jenkins.plugins.instana.util.HttpRequestNameValuePair;
import jenkins.plugins.instana.util.RequestAction;

/**
 * @author Janario Oliveira
 */
public class HttpRequestExecution extends MasterToSlaveCallable<ResponseContentSupplier, RuntimeException> {

	private static final long serialVersionUID = -2066857816168989599L;
	private final String url;
	private final HttpMode httpMode;
	private final HttpHost httpProxy;

	private final String body;
	private final List<HttpRequestNameValuePair> headers;

	private final OutputStream remoteLogger;
	private transient PrintStream localLogger;

	static HttpRequestExecution from(ReleaseEvent http, TaskListener taskListener) {
		String url = http.resolveUrl();
		String body = http.getReleaseName() + " | " + http.getReleaseStartTimestamp() + " | " + http.getReleaseEndTimestamp();
		List<HttpRequestNameValuePair> headers = http.resolveHeaders();

		return new HttpRequestExecution(url, http.resolveHttpMode(), http.resolveProxy(), body,
				headers, taskListener.getLogger());
	}

	static HttpRequestExecution from(ReleaseEventStep step, TaskListener taskListener) {
		String url = step.resolveUrl();

		JSONObject jsonObject =  new JSONObject();
		jsonObject.put("name", (Object) step.getReleaseName());
		jsonObject.put("start", (Object) step.getReleaseStartTimestamp());
		String body = jsonObject.toString();
		List<HttpRequestNameValuePair> headers = step.resolveHeaders();

		return new HttpRequestExecution(url, step.resolveHttpMode(), step.resolveProxy(), body,
				headers, taskListener.getLogger());
	}

	private HttpRequestExecution(
			String url, HttpMode httpMode,
			String httpProxy, String body, List<HttpRequestNameValuePair> headers,
			PrintStream logger
	) {
		this.url = url;
		this.httpMode = httpMode;
		this.httpProxy = StringUtils.isNotBlank(httpProxy) ? HttpHost.create(httpProxy) : null;

		this.body = body;
		this.headers = headers;

		this.localLogger = logger;
		this.remoteLogger = new RemoteOutputStream(new CloseProofOutputStream(logger));
	}

	@Override
	public ResponseContentSupplier call() throws RuntimeException {
		logger().println(body);

		for (HttpRequestNameValuePair header : headers) {
			logger().print(header.getName() + ": ");
			logger().println(header.getMaskValue() ? "*****" : header.getValue());
		}

		try {
			return releaseEventRequest(); //authAndRequest();
		} catch (IOException | InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	private PrintStream logger() {
		if (localLogger == null) {
			try {
				localLogger = new PrintStream(remoteLogger, true, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				throw new IllegalStateException(e);
			}
		}
		return localLogger;
	}

	private ResponseContentSupplier releaseEventRequest() throws IOException, InterruptedException {
		CloseableHttpClient httpclient = null;
		try {
			HttpClientBuilder clientBuilder = HttpClientBuilder.create();

			if (this.httpProxy != null) {
				clientBuilder.setProxy(this.httpProxy);
			}
			HttpClientUtil clientUtil = new HttpClientUtil();
			HttpRequestBase httpRequestBase = clientUtil.createRequestBase(
					new RequestAction(new URL(this.url), this.httpMode,
							this.body,
							null,
							this.headers));

			HttpContext context = new BasicHttpContext();
			httpclient = clientBuilder.build();

			final ResponseContentSupplier response = executeRequest(httpclient, clientUtil, httpRequestBase, context);
			validate(response);
			return response;
		} finally {
			if (httpclient != null) {
				httpclient.close();
			}
		}
	}

	private void validate(ResponseContentSupplier response) throws AbortException{
		if(response.getStatus() != 200){
			logger().println(response.getContent());
			throw new AbortException("Fail: the returned code " + response.getStatus() + " is not: 200");
		}
	}

	private ResponseContentSupplier executeRequest(
			CloseableHttpClient httpclient, HttpClientUtil clientUtil, HttpRequestBase httpRequestBase,
			HttpContext context) throws IOException, InterruptedException {
		ResponseContentSupplier responseContentSupplier;
		try {
			final HttpResponse response = clientUtil.execute(httpclient, context, httpRequestBase, logger());
			// The HttpEntity is consumed by the ResponseContentSupplier
			responseContentSupplier = new ResponseContentSupplier(ResponseHandle.STRING, response);
		} catch (UnknownHostException uhe) {
			logger().println("Treating UnknownHostException(" + uhe.getMessage() + ") as 404 Not Found");
			responseContentSupplier = new ResponseContentSupplier("UnknownHostException as 404 Not Found", 404);
		} catch (SocketTimeoutException | ConnectException ce) {
			logger().println("Treating " + ce.getClass() + "(" + ce.getMessage() + ") as 408 Request Timeout");
			responseContentSupplier = new ResponseContentSupplier(ce.getClass() + "(" + ce.getMessage() + ") as 408 Request Timeout", 408);
		}

		return responseContentSupplier;
	}
}