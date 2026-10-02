package com.lytspeed.orka.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local abuse bounds, not a replacement for upstream distributed DDoS protection. */
public class RequestBoundsFilter extends OncePerRequestFilter {
 private static final int MAX_BODY=1_048_576;
 private record Bucket(long start,int count) {}
 private final Map<String,Bucket> buckets=new HashMap<>();
 synchronized boolean allow(String key,int limit,long now){
  if(buckets.size()>=10000)buckets.entrySet().removeIf(e->now-e.getValue().start>=60000);
  Bucket b=buckets.get(key);
  if(b==null||now-b.start>=60000){if(b==null&&buckets.size()>=10000)return false;b=new Bucket(now,0);}
  if(b.count>=limit)return false;buckets.put(key,new Bucket(b.start,b.count+1));return true;
 }
 private void reject(HttpServletResponse res,int code,String message)throws IOException {res.setStatus(code);res.setContentType("application/json");res.getWriter().write("{\"message\":\""+message+"\"}");}
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException{
  String path=req.getServletPath();if(!path.startsWith("/api/")||req.getMethod().equals("OPTIONS")){chain.doFilter(req,res);return;}
  long now=System.currentTimeMillis();String peer=req.getRemoteAddr(); // Never trust client-supplied forwarding headers.
  boolean allowed=allow("ip:"+peer,1000,now);
  int guest=path.indexOf("/guest/");
  if(allowed&&guest>=0){String token=path.substring(guest+7).split("/",2)[0];allowed=allow("guest:"+peer+":"+Integer.toHexString(token.hashCode()),120,now);}
  if(!allowed){res.setHeader("Retry-After","60");reject(res,429,"Too many requests. Wait a minute before retrying.");return;}
  if(req.getContentLengthLong()>MAX_BODY){reject(res,413,"Request is too large.");return;}
  if(List.of("POST","PUT","PATCH").contains(req.getMethod())){
   byte[] body=req.getInputStream().readNBytes(MAX_BODY+1);if(body.length>MAX_BODY){reject(res,413,"Request is too large.");return;}
   chain.doFilter(new HttpServletRequestWrapper(req){
    @Override public ServletInputStream getInputStream(){var in=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}};}
    @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
   },res);
  }else chain.doFilter(req,res);
 }
}
