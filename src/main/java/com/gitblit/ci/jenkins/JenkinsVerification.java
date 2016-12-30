package com.gitblit.ci.jenkins;

import com.gitblit.models.RepositoryModel;
import com.gitblit.models.TicketModel;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.RefNameUtils;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.GitBlitWebApp;
import com.gitblit.wicket.GitBlitWebSession;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class JenkinsVerification {

    private final RepositoryModel repositoryModel;
    private final Repository repository;
    private final String refName;
    private final String lastCommitSha1;

    public JenkinsVerification(RepositoryModel repositoryModel, Repository repository, String refName, String
            lastCommitSha1) {
        this.repositoryModel = repositoryModel;
        this.repository = repository;
        this.refName = refName;
        this.lastCommitSha1 = lastCommitSha1;
    }

    public boolean startVerification(boolean restart) {
        String requestParamsStr = defineRequestParams(refName, lastCommitSha1);
        String uriStr = repositoryModel.CIUrl + "/gitblit/notifyCommit?" + requestParamsStr;
        URI uri = URI.create(uriStr);
        int code = sendRequest(uri);
        boolean added = false;
        if (200 == code) {
            JenkinsGitNoteUtils.GitNoteBuilder builder = JenkinsGitNoteUtils.createNoteBuilder();
            builder.addBuildInvocationTime(new Date());
            TicketModel.CIScore score = restart ? TicketModel.CIScore.restarted : TicketModel.CIScore.not_started_yet;
            builder.addCiBuildStatus(score);
            builder.addCiJobUrl(uriStr);
            String note = builder.build();
            added = JGitUtils.addNote(repository, lastCommitSha1, note);
        }
        return added;
    }

    private String defineRequestParams(String refName, String lastCommitSha1) {
        Map<String, String> requestParams = new HashMap<>();
        requestParams.put("repository", repositoryModel.name);
        requestParams.put("job", repositoryModel.jobname);
        requestParams.put("branch", refName);
        requestParams.put("user", GitBlitWebSession.get().getUsername());
        requestParams.put("lastCommit", lastCommitSha1);

        long ticketId = RefNameUtils.getTicketId(refName);
        TicketModel ticket = GitBlitWebApp.get().tickets().getTicket(repositoryModel, ticketId);
        if (null != ticket) {
            requestParams.put("ticketTitle", ticket.title);
            requestParams.put("ticketTopic", ticket.topic);
            requestParams.put("ticketType", ticket.type.toString());
            requestParams.put("ticketPriority", ticket.priority.toString());
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : requestParams.entrySet()) {
            sb.append(entry.getKey());
            sb.append("=");
            String value = null == entry.getValue() ? "null" : entry.getValue();
            sb.append(StringUtils.encodeURL(value));
            sb.append("&");
        }
        String res = sb.toString();
        return res.substring(0, res.length() - 1);
    }

    private int sendRequest(URI uri) {
        HttpHost host = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
        HttpClientContext localContext = null;
        HttpClientBuilder clientBuilder = HttpClients.custom();

        if (!repositoryModel.jenkinsUsername.trim().isEmpty()) {
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(new AuthScope(uri.getHost(), uri.getPort()), new
                    UsernamePasswordCredentials(repositoryModel.jenkinsUsername, repositoryModel.jenkinsApiToken));
            clientBuilder.setDefaultCredentialsProvider(credentialsProvider);

            AuthCache authCache = new BasicAuthCache();
            BasicScheme basicAuth = new BasicScheme();
            authCache.put(host, basicAuth);
            localContext = HttpClientContext.create();
            localContext.setAuthCache(authCache);
        }

        CloseableHttpClient client = clientBuilder.build();
        CloseableHttpResponse response = null;
        try {
            HttpGet request = new HttpGet(uri);
            response = client.execute(host, request, localContext);
            int httpCode = response.getStatusLine().getStatusCode();
            return httpCode;
        } catch (ClientProtocolException e) {
            e.printStackTrace();
            return 0;
        } catch (IOException e) {
            e.printStackTrace();
            return 0;
        } finally {
            EntityUtils.consumeQuietly(response.getEntity());
        }
    }

}