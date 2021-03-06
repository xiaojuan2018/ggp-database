package ggp.database.statistics.statistic.implementation;

import ggp.database.statistics.statistic.CounterStatistic;
import ggp.database.statistics.statistic.PerPlayerStatistic;

import java.util.List;

import com.google.appengine.api.datastore.Entity;

public class NetScore extends PerPlayerStatistic<CounterStatistic.NaiveCounter> {
    @SuppressWarnings("unchecked")
    public void updateWithMatch(Entity newMatch) {
        if (newMatch.getProperty("goalValues") == null) return;        
        if (!(Boolean)newMatch.getProperty("isCompleted")) return;
        if (newMatch.getProperty("hashedMatchHostPK") == null) return;
        if ((Boolean)newMatch.getProperty("hasErrors")) return;

        List<String> playerNames = getPlayerNames(newMatch);
        if (playerNames == null) return;
        
        for (int i = 0; i < playerNames.size(); i++) {
            double theScore = ((List<Long>)newMatch.getProperty("goalValues")).get(i);
            getPerPlayerStatistic(playerNames.get(i)).incrementCounter((theScore-50.0)/50.0);
        }
    }

    @Override
    protected CounterStatistic.NaiveCounter getInitialStatistic() {
        return new CounterStatistic.NaiveCounter();
    }
}