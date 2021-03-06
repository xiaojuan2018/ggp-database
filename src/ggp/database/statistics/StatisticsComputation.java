package ggp.database.statistics;

import static com.google.appengine.api.datastore.FetchOptions.Builder.withChunkSize;
import external.JSON.JSONException;
import external.JSON.JSONObject;
import ggp.database.statistics.statistic.PerPlayerStatistic;
import ggp.database.statistics.statistic.Statistic;
import ggp.database.statistics.statistic.implementation.AgonRank;
import ggp.database.statistics.statistic.implementation.AverageMoves;
import ggp.database.statistics.statistic.implementation.AverageScoreOn;
import ggp.database.statistics.statistic.implementation.AverageScoreVersus;
import ggp.database.statistics.statistic.implementation.ComputeRAM;
import ggp.database.statistics.statistic.implementation.ComputeTime;
import ggp.database.statistics.statistic.implementation.ComputedAt;
import ggp.database.statistics.statistic.implementation.DecayedAverageScore;
import ggp.database.statistics.statistic.implementation.EloRank;
import ggp.database.statistics.statistic.implementation.AverageScore;
import ggp.database.statistics.statistic.implementation.ErrorRateForPlayer;
import ggp.database.statistics.statistic.implementation.LastPlayed;
import ggp.database.statistics.statistic.implementation.LastTournamentUpdate;
import ggp.database.statistics.statistic.implementation.Matches;
import ggp.database.statistics.statistic.implementation.MatchesAbandoned;
import ggp.database.statistics.statistic.implementation.MatchesAborted;
import ggp.database.statistics.statistic.implementation.MatchesAverageMoves;
import ggp.database.statistics.statistic.implementation.MatchesAveragePlayers;
import ggp.database.statistics.statistic.implementation.MatchesFinished;
import ggp.database.statistics.statistic.implementation.MatchesScrambled;
import ggp.database.statistics.statistic.implementation.MatchesStartedChart;
import ggp.database.statistics.statistic.implementation.MatchesStatErrors;
import ggp.database.statistics.statistic.implementation.NetScore;
import ggp.database.statistics.statistic.implementation.ObservedGames;
import ggp.database.statistics.statistic.implementation.ObservedPlayers;
import ggp.database.statistics.statistic.implementation.ObservedTournaments;
import ggp.database.statistics.statistic.implementation.PlayerHoursConsumed;
import ggp.database.statistics.statistic.implementation.RoleCorrelationWithSkill;
import ggp.database.statistics.statistic.implementation.RolePlayerAverageScore;
import ggp.database.statistics.statistic.implementation.StatsVersion;
import ggp.database.statistics.statistic.implementation.TournamentMatchesCompleted;
import ggp.database.statistics.statistic.implementation.UpdatedAt;
import ggp.database.statistics.statistic.implementation.WinsVersusPlayerOnGame;
import ggp.database.statistics.stored.FinalGameStats;
import ggp.database.statistics.stored.FinalOverallStats;
import ggp.database.statistics.stored.FinalPlayerStats;
import ggp.database.statistics.stored.FinalTournamentStats;
import ggp.database.statistics.stored.IntermediateStatistics;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.appengine.api.datastore.Cursor;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceConfig;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.QueryResultIterator;
import com.google.appengine.api.datastore.ReadPolicy;

public class StatisticsComputation implements Statistic.Reader {
    public static void computeBatchStatistics() throws IOException {
        Map<String, StatisticsComputation> statsForLabel = new HashMap<String, StatisticsComputation>();
        statsForLabel.put("all", new StatisticsComputation());
        statsForLabel.put("unsigned", new StatisticsComputation());
        
        long nComputeBeganAt = System.currentTimeMillis();
        {
            DatastoreService datastore = DatastoreServiceFactory.getDatastoreService(DatastoreServiceConfig.Builder.withReadPolicy(new ReadPolicy(ReadPolicy.Consistency.EVENTUAL)));        
            Query q = new Query("CondensedMatch");
            q.addSort("startTime");
            PreparedQuery pq = datastore.prepare(q);
            
            long startTime;
            String cursorForContinuation = "";
            
            do {            
                QueryResultIterator<Entity> results;
                if (cursorForContinuation.isEmpty()) {
                    results = pq.asQueryResultIterator(withChunkSize(1000));
                } else {
                    results = pq.asQueryResultIterator(withChunkSize(1000).startCursor(Cursor.fromWebSafeString(cursorForContinuation)));
                }
            
                // Continuation data, so we can pull in multiple queries worth of data
                startTime = System.currentTimeMillis();
                cursorForContinuation = "";
                
                /* Compute the statistics in a single sorted pass over the data */            
                while (results.hasNext()) {
                    if (System.currentTimeMillis() - startTime > 20000) {
                        cursorForContinuation = results.getCursor().toWebSafeString();
                        break;
                    }
                    
                    addAdditionalMatch(statsForLabel, results.next());                    
                }            
            } while (!cursorForContinuation.isEmpty());
        }
        long nComputeFinishedAt = System.currentTimeMillis();
        
        // Save all of the statistics
        Set<String> theLabels = new HashSet<String>(statsForLabel.keySet());
        for (String theLabel : theLabels) {
            statsForLabel.get(theLabel).getStatistic(ComputeTime.class).incrementComputeTime(nComputeFinishedAt - nComputeBeganAt);
            statsForLabel.get(theLabel).finalizeComputation();
            statsForLabel.get(theLabel).saveAs(theLabel);
            statsForLabel.remove(theLabel);
            System.gc();
        }        
    }
    
    public static void incrementallyAddMatch(Entity newMatch) {
        // Load the stored set of statistics computations.
        Set<String> labelsToFetch = getLabelsForMatch(newMatch);
        Map<String, StatisticsComputation> statsForLabel = new HashMap<String, StatisticsComputation>();
        for (String labelToFetch : labelsToFetch) {
            StatisticsComputation r = new StatisticsComputation();
            r.restoreFrom(IntermediateStatistics.loadIntermediateStatistics(labelToFetch));
            statsForLabel.put(labelToFetch, r);
            System.gc();
        }
        
        // Compute statistics by iterating over all of the stored matches in order.
        long nComputeBeganAt = System.currentTimeMillis();
        addAdditionalMatch(statsForLabel, newMatch);
        long nComputeFinishedAt = System.currentTimeMillis();

        // Save all of the computations for future use.
        Set<String> theLabels = new HashSet<String>(statsForLabel.keySet());
        for (String theLabel : theLabels) {
            statsForLabel.get(theLabel).getStatistic(ComputeTime.class).incrementComputeTime(nComputeFinishedAt - nComputeBeganAt);
            statsForLabel.get(theLabel).finalizeComputation();
            statsForLabel.get(theLabel).saveAs(theLabel);
            statsForLabel.remove(theLabel);
            System.gc();
        }        
    }
    
    private static Set<String> getLabelsForMatch(Entity match) {
        Set<String> theLabels = new HashSet<String>();
        theLabels.add("all");
        if (match.getProperty("hashedMatchHostPK") != null) {
            String theHostPK = (String)match.getProperty("hashedMatchHostPK");
            theLabels.add(theHostPK);
        } else {
            theLabels.add("unsigned");
        }
        return theLabels;
    }

    private static void addAdditionalMatch(Map<String, StatisticsComputation> statsForLabel, Entity match) {
        for (String aLabel : getLabelsForMatch(match)) {
            if (!statsForLabel.containsKey(aLabel)) {
                statsForLabel.put(aLabel, new StatisticsComputation());
            }
            statsForLabel.get(aLabel).add(match);
        }
    }

    private Set<Statistic> registeredStatistics;    
    private Set<String> alteredGames;
    private Set<String> alteredPlayers;
    private Set<String> alteredTournaments;
    
    public StatisticsComputation () {
        alteredGames = new HashSet<String>();
        alteredPlayers = new HashSet<String>();
        alteredTournaments = new HashSet<String>();
        registeredStatistics = new HashSet<Statistic>();
        /* TODO: get this working
        for (Statistic aStat : ServiceLoader.load(Statistic.class)) {
            registeredStatistics.add(aStat);
        }*/
        registeredStatistics.add(new AgonRank());
        registeredStatistics.add(new AverageMoves());
        registeredStatistics.add(new AverageScore());
        registeredStatistics.add(new AverageScoreOn());
        registeredStatistics.add(new AverageScoreVersus());        
        registeredStatistics.add(new ComputedAt());
        registeredStatistics.add(new ComputeRAM());
        registeredStatistics.add(new ComputeTime());
        registeredStatistics.add(new DecayedAverageScore());
        registeredStatistics.add(new EloRank());
        registeredStatistics.add(new LastPlayed());
        registeredStatistics.add(new Matches());
        registeredStatistics.add(new MatchesAbandoned());
        registeredStatistics.add(new MatchesAborted());
        registeredStatistics.add(new MatchesAverageMoves());
        registeredStatistics.add(new MatchesAveragePlayers());
        registeredStatistics.add(new MatchesFinished());
        registeredStatistics.add(new MatchesScrambled());
        registeredStatistics.add(new MatchesStartedChart());
        registeredStatistics.add(new MatchesStatErrors());
        registeredStatistics.add(new NetScore());
        registeredStatistics.add(new ObservedGames());
        registeredStatistics.add(new ObservedPlayers());
        registeredStatistics.add(new PlayerHoursConsumed());
        registeredStatistics.add(new RoleCorrelationWithSkill());
        registeredStatistics.add(new RolePlayerAverageScore());
        registeredStatistics.add(new StatsVersion());
        registeredStatistics.add(new UpdatedAt());
        registeredStatistics.add(new WinsVersusPlayerOnGame());
        registeredStatistics.add(new ErrorRateForPlayer());
        registeredStatistics.add(new TournamentMatchesCompleted());
        registeredStatistics.add(new ObservedTournaments());
        registeredStatistics.add(new LastTournamentUpdate());
    }
    
    @SuppressWarnings("unchecked")
    public <T extends Statistic> T getStatistic(Class<T> c) {
        for (Statistic s : registeredStatistics) {
            if (s.getClass().equals(c))
                return (T)s;
        }
        return null;
    }

    public void add(Entity theMatch) {
        for (Statistic s : registeredStatistics) {
            s.updateWithMatch(theMatch);
        }
        alteredPlayers.addAll(PerPlayerStatistic.getPlayerNames(theMatch));
        alteredGames.add(theMatch.getProperty("gameMetaURL").toString());
        if (theMatch.getProperty("tournamentNameFromHost") != null) {
        	alteredTournaments.add((String)theMatch.getProperty("tournamentNameFromHost"));
        }
    }
    
    public void finalizeComputation() {
        for (Statistic s : registeredStatistics) {
            s.finalizeComputation(this);
        }
    }
    
    public void restoreFrom(JSONObject serializedState) {
        for (Statistic s : registeredStatistics) {
            s.loadState(serializedState);
        }
    }
    
    public void saveAs(String theLabel) {
        // Store the statistics as a JSON object in the datastore.
        String activeStat = "None";
        try {
            // Store the intermediate statistics
            JSONObject serializedState = new JSONObject();
            for (Statistic s : registeredStatistics) {
                activeStat = "inter-"+s.getClass().getSimpleName();
                s.saveState(serializedState);
            }
            IntermediateStatistics.saveIntermediateStatistics(theLabel, serializedState);
            
            // Store the overall statistics
            JSONObject overallStats = new JSONObject();
            for (Statistic s : registeredStatistics) {
                for (Statistic.FinalForm f : s.getFinalForms()) {
                    activeStat = s.getClass().getSimpleName() + ":" + f.name;
                    if (f.value != null) {
                        overallStats.put(f.name, f.value);
                    }
                }
            }
            new FinalOverallStats(theLabel).setJSON(overallStats);

            // Store the per-game statistics
            // Only write statistics for games that appeared in added matches
            for (String gameName : alteredGames) {
                JSONObject gameStats = new JSONObject();
                for (Statistic s : registeredStatistics) {
                    for (Statistic.FinalForm f : s.getPerGameFinalForms(gameName)) {
                        activeStat = s.getClass().getSimpleName() + ":" + f.name;
                        if (f.value != null) {
                            gameStats.put(f.name, f.value);
                        }
                    }
                }
                new FinalGameStats(theLabel, gameName).setJSON(gameStats);
            }
            
            // Store the per-tournament statistics
            // Only write statistics for tournaments that appeared in added matches
            for (String tournamentName : alteredTournaments) {
                JSONObject tournamentStats = new JSONObject();
                for (Statistic s : registeredStatistics) {
                    for (Statistic.FinalForm f : s.getPerTournamentFinalForms(tournamentName)) {
                        activeStat = s.getClass().getSimpleName() + ":" + f.name;
                        if (f.value != null) {
                        	tournamentStats.put(f.name, f.value);
                        }
                    }
                }
                new FinalTournamentStats(theLabel, tournamentName).setJSON(tournamentStats);
            }
            
            // Store the per-player statistics
            // Only write statistics for players who appeared in added matches
            for (String playerName : alteredPlayers) {
                JSONObject playerStats = new JSONObject();
                for (Statistic s : registeredStatistics) {
                    for (Statistic.FinalForm f : s.getPerPlayerFinalForms(playerName)) {
                        activeStat = s.getClass().getSimpleName() + ":" + f.name;
                        if (f.value != null) {
                            playerStats.put(f.name, f.value);
                        }
                    }
                }
                new FinalPlayerStats(theLabel, playerName).setJSON(playerStats);
            }
        } catch (JSONException e) {
            throw new RuntimeException(activeStat + ":" + e);
        }        
    }
}