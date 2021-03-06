package ggp.database.statistics;

import ggp.database.statistics.stored.FinalGameStats;
import ggp.database.statistics.stored.FinalOverallStats;
import ggp.database.statistics.stored.FinalPlayerStats;
import ggp.database.statistics.stored.FinalTournamentStats;
import ggp.database.statistics.stored.IntermediateStatistics;

import java.io.IOException;

import javax.servlet.http.*;

import external.JSON.JSONObject;

@SuppressWarnings("serial")
public class MatchStatistics extends HttpServlet {
    public static void respondWithStats(HttpServletResponse resp, String theStatistic) throws IOException {
        JSONObject theResponse = null;
        if (theStatistic != null && theStatistic.indexOf("/") > -1) {
            String theLabel = theStatistic.substring(0, theStatistic.indexOf("/"));
            theStatistic = theStatistic.replaceFirst(theLabel + "/", "");
            if (theStatistic.equals("overall")) {
                theResponse = FinalOverallStats.load(theLabel).getJSON();
            } else if (theStatistic.startsWith("players/")) {
                theStatistic = theStatistic.replaceFirst("players/", "");
                theResponse = FinalPlayerStats.load(theLabel, theStatistic).getJSON();
            } else if (theStatistic.startsWith("games/")) {
                theStatistic = theStatistic.replaceFirst("games/", "");
                theResponse = FinalGameStats.load(theLabel, theStatistic).getJSON();
            } else if (theStatistic.startsWith("tournaments/")) {
                theStatistic = theStatistic.replaceFirst("tournaments/", "");
                theResponse = FinalTournamentStats.load(theLabel, theStatistic).getJSON();            	
            } else if (theStatistic.equals("intermediate")) {
                theResponse = IntermediateStatistics.loadIntermediateStatistics(theLabel);
            }
        }
        if (theResponse != null) {
        	resp.setContentType("application/json");
            resp.getWriter().println(theResponse.toString());
        } else {
            resp.setStatus(404);
        }            
    }
}