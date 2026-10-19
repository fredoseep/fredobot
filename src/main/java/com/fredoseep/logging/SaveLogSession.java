package com.fredoseep.logging;

import java.util.Objects;

/** Integrated-server lifetime identifies a save session; ClientWorld lifetime identifies a dimension. */
public final class SaveLogSession {
    private boolean started;
    private Object server;
    private String label;
    public boolean startsNew(boolean explicitlyLoaded,Object currentServer,String currentLabel){
        boolean fresh=explicitlyLoaded || !started || (currentServer!=null && server!=null?server!=currentServer:!Objects.equals(label,currentLabel));
        started=true;if(currentServer!=null || fresh)server=currentServer;label=currentLabel;return fresh;
    }
}
