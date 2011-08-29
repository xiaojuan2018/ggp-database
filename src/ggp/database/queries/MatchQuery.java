package ggp.database.queries;

import ggp.database.Persistence;
import ggp.database.matches.CondensedMatch;

import java.io.IOException;
import java.util.List;

import javax.jdo.Query;
import javax.servlet.http.HttpServletResponse;

import com.google.appengine.repackaged.org.json.JSONArray;
import com.google.appengine.repackaged.org.json.JSONException;
import com.google.appengine.repackaged.org.json.JSONObject;

public class MatchQuery {
    @SuppressWarnings("unchecked")
    public static void respondToQuery(HttpServletResponse resp, String theRPC) throws IOException {
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
}