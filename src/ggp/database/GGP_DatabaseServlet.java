package ggp.database;

import static com.google.appengine.api.taskqueue.RetryOptions.Builder.withTaskRetryLimit;
import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withUrl;

import ggp.database.matches.CondensedMatch;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import javax.jdo.Query;
import javax.servlet.http.*;

import util.configuration.RemoteResourceLoader;

import com.google.appengine.api.capabilities.CapabilitiesService;
import com.google.appengine.api.capabilities.CapabilitiesServiceFactory;
import com.google.appengine.api.capabilities.Capability;
import com.google.appengine.api.capabilities.CapabilityStatus;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.appengine.repackaged.org.json.JSONArray;
import com.google.appengine.repackaged.org.json.JSONException;
import com.google.appengine.repackaged.org.json.JSONObject;

@SuppressWarnings("serial")
public class GGP_DatabaseServlet extends HttpServlet {
    public void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "*");
        resp.setHeader("Access-Control-Allow-Age", "86400");        
        
        String reqURI = req.getRequestURI();
        if (reqURI.equals("/cron/push_subscribe") || reqURI.equals("/push_subscribe")) {
            ServerState ss = ServerState.loadState();
            ss.rotateValidationToken();
            ss.save();
            String theCallbackURL = URLEncoder.encode("http://database.ggp.org/ingestion/", "UTF-8");
            String theFeedURL = URLEncoder.encode("http://matches.ggp.org/matches/feeds/updatedFeed.atom", "UTF-8");                                    
            RemoteResourceLoader.postRawWithTimeout("http://pubsubhubbub.appspot.com/", "hub.callback=" + theCallbackURL + "&hub.mode=subscribe&hub.topic=" + theFeedURL + "&hub.verify=sync&hub.verify_token=" + ss.getValidationToken(), 5000);
            resp.setStatus(200);
            resp.getWriter().println("PuSH subscription sent.");            
            return;
        }
        
        if (reqURI.equals("/ingestion/")) {
            // Handle the PuSH subscriber confirmation
            boolean isValid = true;
            ServerState ss = ServerState.loadState();
            isValid &= req.getParameter("hub.topic").equals("http://matches.ggp.org/matches/feeds/updatedFeed.atom");
            isValid &= req.getParameter("hub.verify_token").equals(ss.getValidationToken());
            if (isValid) {
                resp.setStatus(200);
                resp.getWriter().print(req.getParameter("hub.challenge"));
                resp.getWriter().close();
            } else {
                resp.setStatus(404);
                resp.getWriter().close();
            }            
            return;
        } else if (reqURI.equals("/tasks/ingest_match")) {
            // Actually ingest a match, in the task queue.
            String theMatchURL = req.getParameter("matchURL");
            JSONObject theMatchJSON = RemoteResourceLoader.loadJSON(theMatchURL);
            CondensedMatch.storeCondensedMatchJSON(theMatchURL, theMatchJSON);
            resp.setStatus(200);
            return;            
        }      

        // Handle requests for browser channel subscriptions.
        if (reqURI.startsWith("/subscribe/")) {
            String theSub = reqURI.replace("/subscribe/", "");
            if (theSub.equals("channel.js")) {
                // If they're requesting a channel token, we can handle
                // that immediately without needing further parsing.                
                ChannelService.writeChannelToken(resp);
                resp.setStatus(200);
                return;
            } else if (theSub.startsWith("match/")) {
                theSub = theSub.substring("match/".length());
                // Parse out the match URL and the channel token, and subscribe
                // that channel token to that match URL.
                if (theSub.contains("/clientId=")) {
                    String theID = theSub.substring(theSub.indexOf("/clientId=")+("/clientId=".length()));
                    String theKey = theSub.substring(0, theSub.indexOf("/clientId="))+"/";
                    ChannelService.registerChannelForMatch(resp, theKey, theID);
                    resp.setStatus(200);
                    return;
                }
            } else if (theSub.startsWith("query/")) {
                // Parse out the query string and the channel token, and subscribe
                // that channel token to that match URL.
                if (theSub.contains("/clientId=")) {
                    String theID = theSub.substring(theSub.indexOf("/clientId=")+("/clientId=".length()));
                    String theKey = theSub.substring(0, theSub.indexOf("/clientId="));
                    if (UpdateRegistry.verifyKey(theKey)) {
                        UpdateRegistry.registerClient(theKey, theID);
                        resp.getWriter().println("Successfully subscribed to: " + theKey);
                        resp.setStatus(200);
                        return;
                    }
                }
            }
            resp.setStatus(404);
            return;
        }

        if (reqURI.startsWith("/data/")) {
            respondToRPC(resp, reqURI.replaceFirst("/data/", ""));
            return;
        }
        if (reqURI.startsWith("/query/")) {
            respondToQuery(resp, reqURI.replaceFirst("/query/", ""));
            return;
        }        

        boolean writeAsBinary = false;
        if (reqURI.endsWith("/")) {
            reqURI += "index.html";
        }
        if (reqURI.endsWith(".html")) {
            resp.setContentType("text/html");
        } else if (reqURI.endsWith(".xml")) {
            resp.setContentType("application/xml");
        } else if (reqURI.endsWith(".xsl")) {
            resp.setContentType("application/xml");
        } else if (reqURI.endsWith(".js")) {
            resp.setContentType("text/javascript");   
        } else if (reqURI.endsWith(".json")) {
            resp.setContentType("text/javascript");
        } else if (reqURI.endsWith(".png")) {
            resp.setContentType("image/png");
            writeAsBinary = true;
        } else if (reqURI.endsWith(".ico")) {
            resp.setContentType("image/png");
            writeAsBinary = true;
        } else {
            resp.setContentType("text/plain");
        }

        try {
            if (writeAsBinary) {
                writeStaticBinaryPage(resp, reqURI.substring(1));
            } else {
                // Temporary limits on caching, for during development.
                resp.setHeader("Cache-Control", "no-cache");
                resp.setHeader("Pragma", "no-cache");
                writeStaticTextPage(resp, reqURI.substring(1));
            }
        } catch(IOException e) {
            resp.setStatus(404);
        }
    }
    
    public void writeStaticTextPage(HttpServletResponse resp, String theURI) throws IOException {
        FileReader fr = new FileReader(theURI);
        BufferedReader br = new BufferedReader(fr);
        StringBuffer response = new StringBuffer();
        
        String line;
        while( (line = br.readLine()) != null ) {
            response.append(line + "\n");
        }

        resp.getWriter().println(response.toString());
    }
    
    public void writeStaticBinaryPage(HttpServletResponse resp, String theURI) throws IOException {
        InputStream in = new FileInputStream(theURI);
        byte[] buf = new byte[1024];
        while (in.read(buf) > 0) {
            resp.getOutputStream().write(buf);
        }
        in.close();        
    }    

    @SuppressWarnings("unchecked")
    public void respondToQuery(HttpServletResponse resp, String theRPC) throws IOException {
        JSONObject theResponse = null;
        if (theRPC.startsWith("filter")) {
            String[] theSplit = theRPC.split(",");
            String theVerb = theSplit[0];
            String theDomain = theSplit[1];
            String theHost = theSplit[2];
            
            if (theVerb.length() == 0 || theDomain.length() == 0 || theHost.length() == 0) {
                resp.setStatus(404);
                return;
            }
            Query query = Persistence.getPersistenceManager().newQuery(CondensedMatch.class);
            query.setFilter("hashedMatchHostPK == '" + theHost + "'");
            if (theVerb.equals("filterPlayer")) {
                String thePlayer = theSplit[3];
                if (thePlayer.length() == 0) {
                    resp.setStatus(404);
                    return;
                }
                query.setFilter("hashedMatchHostPK == '" + theHost + "' && playerNamesFromHost == '" + thePlayer + "'");
            } else if (theVerb.equals("filterGame")) {
                String theGame = theSplit[3];
                if (theGame.length() == 0) {
                    resp.setStatus(404);
                    return;
                }                
                query.setFilter("hashedMatchHostPK == '" + theHost + "' && gameMetaURL == '" + theGame + "'");
            } else if (theVerb.equals("filterActiveSet")) {
                String sixHoursAgo = "" + (System.currentTimeMillis() - 21600000L);
                query.setFilter("hashedMatchHostPK == '" + theHost + "' && isCompleted == false && startTime > " + sixHoursAgo);
            } else if (!theVerb.equals("filter")) {
                resp.setStatus(404);
                return;
            }
            if (theDomain.equals("recent")) {            
                query.setOrdering("startTime desc");
                query.setRange(0, 50);
            } else {
                resp.setStatus(404);
                return;
            }
            
            JSONArray theArray = new JSONArray();
            try {
                List<CondensedMatch> results = (List<CondensedMatch>) query.execute();
                if (!results.isEmpty()) {
                    for (CondensedMatch e : results) {
                        if (theVerb.equals("filterActiveSet")) {
                            theArray.put(e.getMatchURL());
                        } else {
                            theArray.put(e.getMatchJSON());
                        }
                    }
                } else {
                    // ... no results ...
                }
            } finally {
                query.closeAll();
            }
            
            try {
                theResponse = new JSONObject();
                theResponse.put("queryMatches", theArray);
            } catch (JSONException je) {
                ;
            }
        }
        if (theResponse != null) {
            resp.getWriter().println(theResponse.toString());
        } else {
            resp.setStatus(404);
        }
    }
    
    public void respondToRPC(HttpServletResponse resp, String theRPC) throws IOException {
        JSONObject theResponse = null;
        if (theRPC.startsWith("serverState")) {
            String theProperty = theRPC.replaceFirst("serverState/", "");                                
            if (theProperty.equals("overall")) {
                ServerState ss = ServerState.loadState();
                try {
                    JSONObject theState = new JSONObject();
                    theState.put("mostRecentUpdate", ss.getMostRecentUpdate());
                    theState.put("mostRecentUpdateDate", ss.getMostRecentUpdateWhen().getTime());
                    List<Long> updateTimesList = new ArrayList<Long>();
                    for(int i = 0; i < ss.getUpdateTimes().size(); i++) {
                        updateTimesList.add(ss.getUpdateTimes().get(i).getTime());
                    }
                    theState.put("recentUpdateTimes", updateTimesList);
                    theResponse = theState;
                } catch (JSONException e) {
                    ;
                }
            }
        }
        if (theResponse != null) {
            resp.getWriter().println(theResponse.toString());
        } else {
            resp.setStatus(404);
        }            
    }

    public void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "*");
        resp.setHeader("Access-Control-Allow-Age", "86400");
        
        if (req.getRequestURI().equals("/ingestion/")) {
            BufferedReader br = new BufferedReader(new InputStreamReader(req.getInputStream()));
            int contentLength = Integer.parseInt(req.getHeader("Content-Length").trim());
            StringBuilder theInput = new StringBuilder();
            for (int i = 0; i < contentLength; i++) {
                theInput.append((char)br.read());
            }
            String in = theInput.toString().trim();
            
            String theLink = in.replace("http://matches.ggp.org/matches/feeds/updatedFeed.atom", "");
            theLink = theLink.replace("http://matches.ggp.org/matches/feeds/completedFeed.atom", "");
            theLink = theLink.substring(theLink.indexOf("<link href=\"http://matches.ggp.org/matches/"));
            theLink = theLink.substring("<link href=\"http://matches.ggp.org/matches/".length(), theLink.indexOf("\"/>"));
            theLink = "http://matches.ggp.org/matches/" + theLink;

            ServerState ss = ServerState.loadState();
            ss.addUpdate(theLink);
            ss.save();
            
            QueueFactory.getDefaultQueue().add(withUrl("/tasks/ingest_match").method(Method.GET).param("matchURL", theLink).retryOptions(withTaskRetryLimit(2)));

            resp.setStatus(200);
            resp.getWriter().close();
        }
    }

    public boolean isDatastoreWriteable() {
        CapabilitiesService service = CapabilitiesServiceFactory.getCapabilitiesService();
        CapabilityStatus status = service.getStatus(Capability.DATASTORE_WRITE).getStatus();
        return (status != CapabilityStatus.DISABLED);
    }

    public void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {  
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "*");
        resp.setHeader("Access-Control-Allow-Age", "86400");
    }    
}