package com.adsamcik.signalcollector.Data;

import java.util.List;

public class Stat {
    public String name, type;
    public boolean showPosition;
    public List<StatData> statData;

    public Stat(String name, String type, boolean showPosition, List<StatData> statData) {
        this.name = name;
        this.type = type;
        this.showPosition = showPosition;
        this.statData = statData;
    }
}
