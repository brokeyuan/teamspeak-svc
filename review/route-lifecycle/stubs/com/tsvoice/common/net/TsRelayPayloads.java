package com.tsvoice.common.net;
public final class TsRelayPayloads {
 public record RelayOpenPayload(byte[] frame){public static final Type<RelayOpenPayload> TYPE=new Type<>("teamspeak-svc","relay_open_v1");public Type<RelayOpenPayload> type(){return TYPE;}}
 public record RelayOpenedPayload(byte[] frame){public static final Type<RelayOpenedPayload> TYPE=new Type<>("teamspeak-svc","relay_opened_v1");public Type<RelayOpenedPayload> type(){return TYPE;}}
 public record RelayDataC2sPayload(byte[] frame){public static final Type<RelayDataC2sPayload> TYPE=new Type<>("teamspeak-svc","relay_data_c2s_v1");public Type<RelayDataC2sPayload> type(){return TYPE;}}
 public record RelayDataS2cPayload(byte[] frame){public static final Type<RelayDataS2cPayload> TYPE=new Type<>("teamspeak-svc","relay_data_s2c_v1");public Type<RelayDataS2cPayload> type(){return TYPE;}}
  public record RelayRouteBarrierC2sPayload(byte[] frame){public static final Type<RelayRouteBarrierC2sPayload> TYPE=new Type<>("teamspeak-svc","relay_route_barrier_c2s_v1");public Type<RelayRouteBarrierC2sPayload> type(){return TYPE;}}public record RelayClosePayload(byte[] frame){public static final Type<RelayClosePayload> TYPE=new Type<>("teamspeak-svc","relay_close_v1");public Type<RelayClosePayload> type(){return TYPE;}}
 public record RelayClosedPayload(byte[] frame){public static final Type<RelayClosedPayload> TYPE=new Type<>("teamspeak-svc","relay_closed_v1");public Type<RelayClosedPayload> type(){return TYPE;}}
 public record RelayErrorPayload(byte[] frame){public static final Type<RelayErrorPayload> TYPE=new Type<>("teamspeak-svc","relay_error_v1");public Type<RelayErrorPayload> type(){return TYPE;}}
 public static final class Type<P> {
  private final String ns;private final String path;
  public Type(String ns,String path){this.ns=ns;this.path=path;}
  public String toString(){return ns+":"+path;}
  public boolean equals(Object o){return o instanceof Type<?> t && t.ns.equals(ns)&&t.path.equals(path);}
  public int hashCode(){return ns.hashCode()*31+path.hashCode();}
 }
 public static void register(){}
 public static boolean isRegistered(){return true;}
}