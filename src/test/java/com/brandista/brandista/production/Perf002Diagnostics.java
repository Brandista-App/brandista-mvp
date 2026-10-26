package com.brandista.brandista.production;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.converter.*;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import javax.sql.DataSource;
import java.lang.management.*;
import java.lang.reflect.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;

/** Imported only by the opt-in, disposable load test. No values, SQL binds or identities recorded. */
@TestConfiguration
public class Perf002Diagnostics {
    static final ThreadMXBean threads=ManagementFactory.getThreadMXBean();
    static final ThreadLocal<Trace> current=new ThreadLocal<>();
    static final ThreadLocal<Long> queueWait=new ThreadLocal<>();
    static final ConcurrentLinkedQueue<Map<String,Object>> completed=new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<Map<String,Object>> threadSamples=new ConcurrentLinkedQueue<>();
    static final java.util.concurrent.atomic.AtomicInteger captured=new java.util.concurrent.atomic.AtomicInteger(),dropped=new java.util.concurrent.atomic.AtomicInteger(),inflight=new java.util.concurrent.atomic.AtomicInteger();
    static long cpu() { return threads.isCurrentThreadCpuTimeSupported()?threads.getCurrentThreadCpuTime():-1; }
    static class Trace {
        final String route;
        final Map<String,long[]> timings=new LinkedHashMap<>();
        String phase="request";
        int connectionsHeld,peakConnectionsHeld,bcryptConnectionsAtEntry=-1;
        Trace(String route) {this.route=route;}
        void add(String name,long wall,long cpu) {
            var value=timings.computeIfAbsent(name,k->new long[3]);
            value[0]++;value[1]+=wall;if(cpu>=0)value[2]+=cpu;
        }
    }
    interface Action { Object run() throws Throwable; }
    static Object timed(String name,Action action) throws Throwable {
        Trace trace=current.get();if(trace==null)return action.run();
        long wall=System.nanoTime(),cpu=cpu();
        try{return action.run();}finally{trace.add(name,System.nanoTime()-wall,cpu<0?-1:cpu()-cpu);}
    }
    static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException exception){throw exception.getCause();}
    }
    @Bean static BeanPostProcessor perf002BeanTimings() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean,String name) throws BeansException {
                if(name.equals("dataSource") && bean instanceof DataSource) {
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(),new Class[]{DataSource.class},(proxy,method,args)-> {
                        if(!method.getName().equals("getConnection")||current.get()==null)return invoke(bean,method,args);
                        Trace owner=current.get();
                        Connection connection=(Connection)timed("db.acquire",()->invoke(bean,method,args));
                        long borrowed=System.nanoTime();
                        String phase=owner.phase;
                        owner.connectionsHeld++;owner.peakConnectionsHeld=Math.max(owner.peakConnectionsHeld,owner.connectionsHeld);
                        var closed=new java.util.concurrent.atomic.AtomicBoolean();
                        return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)-> {
                            try{return invoke(connection,m,a);}finally {
                                if(m.getName().equals("close")&&closed.compareAndSet(false,true)) {
                                    long held=System.nanoTime()-borrowed;
                                    owner.add("db.hold",held,-1);owner.add("db.hold."+phase,held,-1);owner.connectionsHeld--;
                                }
                            }
                        });
                    });
                }
                String timing=switch(name) {
                    case "passwordEncoder" -> "auth.bcrypt";
                    case "userDetailsService" -> "auth.userDetails";
                    case "authenticationManager" -> "auth.manager";
                    case "jwtEncoder" -> "auth.jwt";
                    case "loginService" -> "auth.loginService";
                    case "currentAccountAuthorities" -> "auth.currentAccount";
                    case "userRepository" -> "repository.user";
                    case "platformGrantRepository" -> "repository.grant";
                    default -> null;
                };
                if(timing==null)return bean;
                var factory=new ProxyFactory(bean);
                if(name.equals("loginService")||name.equals("currentAccountAuthorities"))factory.setProxyTargetClass(true);
                factory.addAdvice((MethodInterceptor)call-> {
                    Trace trace=current.get();String method=call.getMethod().getName();
                    if(trace==null || method.equals("toString") || method.equals("equals") || method.equals("hashCode"))return call.proceed();
                    if(name.equals("passwordEncoder")&&!method.equals("matches"))return call.proceed();
                    if(name.equals("passwordEncoder"))trace.bcryptConnectionsAtEntry=trace.connectionsHeld;
                    if(name.equals("userRepository")&&!method.startsWith("find"))return call.proceed();
                    if(name.equals("platformGrantRepository")&&!method.startsWith("exists"))return call.proceed();
                    String previous=trace.phase;
                    if(name.equals("userDetailsService"))trace.phase="userDetails";
                    if(name.equals("currentAccountAuthorities"))trace.phase="currentAccount";
                    String key=timing.startsWith("repository.")?timing+"."+method+"."+trace.phase:timing;
                    try{return timed(key,call::proceed);}finally{trace.phase=previous;}
                });
                return factory.getProxy();
            }
        };
    }
    @Bean FilterRegistrationBean<Filter> perf002RequestTiming() {
        var registration=new FilterRegistrationBean<Filter>();
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setFilter((request,response,chain)-> {
            Long queued=queueWait.get();queueWait.remove();
            var http=(HttpServletRequest)request;
            String uri=http.getRequestURI();
            if(!uri.startsWith("/api/v1/")){chain.doFilter(request,response);return;}
            String route=uri.equals("/api/v1/auth/login")?"login":uri.equals("/api/v1/products")?"search":
                    (uri.startsWith("/api/v1/customers/")||uri.startsWith("/api/v1/merchant/"))?"protected":"other";
            Trace trace=new Trace(route);current.set(trace);
            inflight.incrementAndGet();
            long wall=System.nanoTime(),cpu=cpu();
            ThreadInfo before=threads.getThreadInfo(Thread.currentThread().threadId());
            try{chain.doFilter(request,response);}finally {
                trace.add("http.server",System.nanoTime()-wall,cpu<0?-1:cpu()-cpu);
                if(queued!=null)trace.add("http.dispatchQueue",queued,-1);
                ThreadInfo after=threads.getThreadInfo(Thread.currentThread().threadId());
                var record=new LinkedHashMap<String,Object>();record.put("route",route);
                record.put("status",((HttpServletResponse)response).getStatus());
                record.put("timingsNanos",trace.timings);
                record.put("peakConnectionsHeld",trace.peakConnectionsHeld);
                record.put("bcryptConnectionsAtEntry",trace.bcryptConnectionsAtEntry<0?null:trace.bcryptConnectionsAtEntry);
                record.put("threadBlockedMs",before.getBlockedTime()<0?null:after.getBlockedTime()-before.getBlockedTime());
                record.put("threadWaitedMs",before.getWaitedTime()<0?null:after.getWaitedTime()-before.getWaitedTime());
                if(captured.incrementAndGet()<=30000)completed.add(record);else dropped.incrementAndGet();
                current.remove();inflight.decrementAndGet();
            }
        });
        return registration;
    }
    @Bean WebMvcConfigurer perf002SerializationTiming() {
        return new WebMvcConfigurer() {
            @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                for(int i=0;i<converters.size();i++) {
                    var converter=converters.get(i);
                    if(!(converter instanceof org.springframework.http.converter.json.MappingJackson2HttpMessageConverter))continue;
                    // Delegate to the original configured converter; do not serialize a second time.
                    converters.set(i,(HttpMessageConverter<?>)Proxy.newProxyInstance(converter.getClass().getClassLoader(),
                            new Class[]{GenericHttpMessageConverter.class},(proxy,method,args)->method.getName().equals("write")
                                    ?timed("http.jsonWrite",()->invoke(converter,method,args)):invoke(converter,method,args)));
                }
            }
        };
    }
    @Bean(destroyMethod="close") Lifetime perf002ExecutorTiming() {return new Lifetime();}
    static class Lifetime implements ApplicationListener<ServletWebServerInitializedEvent>,AutoCloseable {
        ScheduledExecutorService sampler;
        org.apache.tomcat.util.threads.ThreadPoolExecutor original;
        org.apache.coyote.ProtocolHandler protocol;
        @Override public void onApplicationEvent(ServletWebServerInitializedEvent event) {
            if(event.getApplicationContext().getServerNamespace()!=null)return;
            var server=(TomcatWebServer)event.getWebServer();
            protocol=server.getTomcat().getConnector().getProtocolHandler();
            if(!(protocol.getExecutor() instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor pool))throw new IllegalStateException("Unexpected Tomcat executor; no queue measurements available");
            original=pool;
            // Delegate unchanged scheduling to Tomcat's original pool/TaskQueue.
            protocol.setExecutor(command->{long entered=System.nanoTime();pool.execute(()-> {
                queueWait.set(System.nanoTime()-entered);
                try{command.run();}finally{queueWait.remove();}
            });});
            if(threads.isThreadContentionMonitoringSupported())threads.setThreadContentionMonitoringEnabled(true);
            sampler=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"perf002-observer");thread.setDaemon(true);return thread;});
            sampler.scheduleAtFixedRate(()-> {
                var states=new TreeMap<String,Integer>();
                for(ThreadInfo info:threads.getThreadInfo(threads.getAllThreadIds()))
                    if(info!=null&&info.getThreadName().startsWith("http-nio-"))states.merge(info.getThreadState().name(),1,Integer::sum);
                threadSamples.add(Map.of("time",java.time.Instant.now().toString(),"poolSize",pool.getPoolSize(),
                        "busy",pool.getActiveCount(),"queued",pool.getQueue().size(),"completedTasks",pool.getCompletedTaskCount(),"httpThreadStates",states));
            },0,1,TimeUnit.SECONDS);
        }
        @Override public void close() {
            if(sampler!=null)sampler.shutdownNow();
            if(protocol!=null)protocol.setExecutor(original);
            if(original!=null)original.shutdownNow();
        }
    }
    static List<Map<String,Object>> drain(ConcurrentLinkedQueue<Map<String,Object>> queue) {
        var records=new ArrayList<Map<String,Object>>();Map<String,Object> record;
        while((record=queue.poll())!=null)records.add(record);return records;
    }
    static void clear() {drain(completed);drain(threadSamples);captured.set(0);dropped.set(0);}
    static Map<String,Object> snapshot() {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(inflight.get()>0&&System.nanoTime()<deadline)java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        var result=Map.<String,Object>of("requests",drain(completed),"threadSamples",drain(threadSamples),
                "inflightAtSnapshot",inflight.get(),"droppedTraces",dropped.getAndSet(0),
                "timeUnits","timingsNanos values: [invocations, total wall nanoseconds, total thread CPU nanoseconds]; hold/queue CPU not measured",
                "threadCpuAvailable",threads.isCurrentThreadCpuTimeSupported()&&threads.isThreadCpuTimeEnabled(),
                "queueScope","socket-dispatch runnable enqueue to start; unavailable for requests without a fresh dispatch. Excludes network/kernel accept backlog.");
        captured.set(0);return result;
    }
}
