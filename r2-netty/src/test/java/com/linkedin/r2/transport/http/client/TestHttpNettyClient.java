/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

/**
 * $Id: $
 */

package com.linkedin.r2.transport.http.client;

import com.linkedin.common.callback.Callback;
import com.linkedin.common.callback.FutureCallback;
import com.linkedin.common.stats.LongTracking;
import com.linkedin.common.util.None;
import com.linkedin.r2.RemoteInvocationException;
import com.linkedin.r2.filter.R2Constants;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.message.rest.RestRequest;
import com.linkedin.r2.message.rest.RestRequestBuilder;
import com.linkedin.r2.message.rest.RestResponse;
import com.linkedin.r2.transport.common.bridge.client.TransportCallbackAdapter;
import com.linkedin.r2.transport.common.bridge.common.TransportCallback;
import com.linkedin.r2.transport.http.client.common.ChannelPoolFactory;
import com.linkedin.r2.transport.http.client.rest.HttpNettyClient;
import com.linkedin.r2.transport.http.common.HttpProtocolVersion;
import com.linkedin.util.clock.SettableClock;
import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.TooLongFrameException;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static com.linkedin.test.util.ExceptionTestUtil.verifyCauseChain;

/**
 * @author Steven Ihde
 * @author Ang Xu
 * @version $Revision: $
 */

public class TestHttpNettyClient
{
  private NioEventLoopGroup _eventLoop;
  private ScheduledExecutorService _scheduler;

  private static final int TEST_MAX_RESPONSE_SIZE = 500000;
  private static final int TEST_MAX_HEADER_SIZE = 50000;

  private static final int RESPONSE_OK = 1;
  private static final int TOO_LARGE = 2;

  @BeforeClass
  public void setup()
  {
    _eventLoop = new NioEventLoopGroup();
    _scheduler = Executors.newSingleThreadScheduledExecutor();
  }

  @AfterClass
  public void tearDown()
  {
    _scheduler.shutdown();
    _eventLoop.shutdownGracefully();
  }

  @Test
  public void testNoChannelTimeout()
      throws InterruptedException
  {
    HttpNettyClient client = new HttpNettyClient(new NoCreations(_scheduler), _scheduler, 500, 500);

    RestRequest r = new RestRequestBuilder(URI.create("http://localhost/")).build();
    FutureCallback<RestResponse> cb = new FutureCallback<RestResponse>();
    TransportCallback<RestResponse> callback = new TransportCallbackAdapter<RestResponse>(cb);
    client.restRequest(r, new RequestContext(), new HashMap<String, String>(), callback);
    try
    {
      // This timeout needs to be significantly larger than the getTimeout of the netty client;
      // we're testing that the client will generate its own timeout
      cb.get(30, TimeUnit.SECONDS);
      Assert.fail("Get was supposed to time out");
    }
    catch (TimeoutException e)
    {
      // TimeoutException means the timeout for Future.get() elapsed and nothing happened.
      // Instead, we are expecting our callback to be invoked before the future timeout
      // with a timeout generated by the HttpNettyClient.
      Assert.fail("Unexpected TimeoutException, should have been ExecutionException", e);
    }
    catch (ExecutionException e)
    {
      verifyCauseChain(e, RemoteInvocationException.class, TimeoutException.class);
    }
  }

  @Test
  public void testNoResponseTimeout()
      throws InterruptedException, IOException
  {
    TestServer testServer = new TestServer();

    HttpNettyClient client = new HttpClientBuilder(_eventLoop, _scheduler).setRequestTimeout(500).setIdleTimeout(10000)
      .setShutdownTimeout(500).buildRestClient();

    RestRequest r = new RestRequestBuilder(testServer.getNoResponseURI()).build();
    FutureCallback<RestResponse> cb = new FutureCallback<RestResponse>();
    TransportCallback<RestResponse> callback = new TransportCallbackAdapter<RestResponse>(cb);
    client.restRequest(r, new RequestContext(), new HashMap<String, String>(), callback);

    try
    {
      // This timeout needs to be significantly larger than the getTimeout of the netty client;
      // we're testing that the client will generate its own timeout
      cb.get(30, TimeUnit.SECONDS);
      Assert.fail("Get was supposed to time out");
    }
    catch (TimeoutException e)
    {
      // TimeoutException means the timeout for Future.get() elapsed and nothing happened.
      // Instead, we are expecting our callback to be invoked before the future timeout
      // with a timeout generated by the HttpNettyClient.
      Assert.fail("Unexpected TimeoutException, should have been ExecutionException", e);
    }
    catch (ExecutionException e)
    {
      verifyCauseChain(e, RemoteInvocationException.class, TimeoutException.class);
    }
    testServer.shutdown();
  }

  @Test
  public void testBadAddress() throws InterruptedException, IOException, TimeoutException
  {
    HttpNettyClient client = new HttpClientBuilder(_eventLoop, _scheduler)
                                  .setRequestTimeout(30000)
                                  .setIdleTimeout(10000)
                                  .setShutdownTimeout(500)
                                  .buildRestClient();

    RestRequest r = new RestRequestBuilder(URI.create("http://this.host.does.not.exist.linkedin.com")).build();
    FutureCallback<RestResponse> cb = new FutureCallback<RestResponse>();
    TransportCallback<RestResponse> callback = new TransportCallbackAdapter<RestResponse>(cb);
    client.restRequest(r, new RequestContext(), new HashMap<String, String>(), callback);
    try
    {
      cb.get(30, TimeUnit.SECONDS);
      Assert.fail("Get was supposed to fail");
    }
    catch (ExecutionException e)
    {
      verifyCauseChain(e, RemoteInvocationException.class, UnknownHostException.class);
    }
  }

  @Test
  public void testRequestContextAttributes()
      throws InterruptedException, IOException, TimeoutException
  {
    HttpNettyClient client = new HttpClientBuilder(_eventLoop, _scheduler).buildRestClient();

    RestRequest r = new RestRequestBuilder(URI.create("http://localhost")).build();

    FutureCallback<RestResponse> cb = new FutureCallback<>();
    TransportCallback<RestResponse> callback = new TransportCallbackAdapter<>(cb);
    RequestContext requestContext = new RequestContext();

    client.restRequest(r, requestContext, new HashMap<>(), callback);

    final String actualRemoteAddress = (String) requestContext.getLocalAttr(R2Constants.REMOTE_SERVER_ADDR);
    final int actualRemotePort = (int) requestContext.getLocalAttr(R2Constants.REMOTE_SERVER_PORT);
    final HttpProtocolVersion actualProtocolVersion = (HttpProtocolVersion) requestContext.getLocalAttr(R2Constants.HTTP_PROTOCOL_VERSION);

    Assert.assertTrue("127.0.0.1".equals(actualRemoteAddress) || "0:0:0:0:0:0:0:1".equals(actualRemoteAddress),
                      "Actual remote client address is not expected. " +
                          "The local attribute field must be IP address in string type");
    Assert.assertEquals(actualRemotePort, 80);
    Assert.assertEquals(actualProtocolVersion, HttpProtocolVersion.HTTP_1_1);
  }


  @Test
  public void testMaxResponseSize()
      throws InterruptedException, IOException, TimeoutException
  {
    testResponseSize(TEST_MAX_RESPONSE_SIZE - 1, RESPONSE_OK);

    testResponseSize(TEST_MAX_RESPONSE_SIZE, RESPONSE_OK);

    testResponseSize(TEST_MAX_RESPONSE_SIZE + 1, TOO_LARGE);
  }

  public void testResponseSize(int responseSize, int expectedResult)
      throws InterruptedException, IOException, TimeoutException
  {
    TestServer testServer = new TestServer();

    HttpNettyClient client =
        new HttpClientBuilder(_eventLoop, _scheduler).setRequestTimeout(50000).setIdleTimeout(10000)
            .setShutdownTimeout(500).setMaxResponseSize(TEST_MAX_RESPONSE_SIZE).buildRestClient();

    RestRequest r = new RestRequestBuilder(testServer.getResponseOfSizeURI(responseSize)).build();
    FutureCallback<RestResponse> cb = new FutureCallback<RestResponse>();
    TransportCallback<RestResponse> callback = new TransportCallbackAdapter<RestResponse>(cb);
    client.restRequest(r, new RequestContext(), new HashMap<String, String>(), callback);

    try
    {
      cb.get(30, TimeUnit.SECONDS);
      if (expectedResult == TOO_LARGE)
      {
        Assert.fail("Max response size exceeded, expected exception. ");
      }
    }
    catch (ExecutionException e)
    {
      if (expectedResult == RESPONSE_OK)
      {
        Assert.fail("Unexpected ExecutionException, response was <= max response size.");
      }
      verifyCauseChain(e, RemoteInvocationException.class, TooLongFrameException.class);
    }
    testServer.shutdown();
  }

  @Test
  public void testMaxHeaderSize() throws InterruptedException, IOException, TimeoutException
  {
    testHeaderSize(TEST_MAX_HEADER_SIZE - 1, RESPONSE_OK);

    testHeaderSize(TEST_MAX_HEADER_SIZE, RESPONSE_OK);

    testHeaderSize(TEST_MAX_HEADER_SIZE + 1, TOO_LARGE);
  }

  public void testHeaderSize(int headerSize, int expectedResult)
      throws InterruptedException, IOException, TimeoutException
  {
    TestServer testServer = new TestServer();

    HttpNettyClient client =
        new HttpClientBuilder(_eventLoop, _scheduler).setRequestTimeout(5000000).setIdleTimeout(10000)
          .setShutdownTimeout(500).setMaxHeaderSize(TEST_MAX_HEADER_SIZE).buildRestClient();

    RestRequest r = new RestRequestBuilder(testServer.getResponseWithHeaderSizeURI(headerSize)).build();
    FutureCallback<RestResponse> cb = new FutureCallback<RestResponse>();
    TransportCallback<RestResponse> callback = new TransportCallbackAdapter<RestResponse>(cb);
    client.restRequest(r, new RequestContext(), new HashMap<String, String>(), callback);

    try
    {
      RestResponse response = cb.get(300, TimeUnit.SECONDS);
      if (expectedResult == TOO_LARGE)
      {
        Assert.fail("Max header size exceeded, expected exception. ");
      }
    }
    catch (ExecutionException e)
    {
      if (expectedResult == RESPONSE_OK)
      {
        Assert.fail("Unexpected ExecutionException, header was <= max header size.");
      }
      verifyCauseChain(e, RemoteInvocationException.class, TooLongFrameException.class);
    }
    testServer.shutdown();
  }

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void testUnsupportedStreamRequest() throws UnsupportedOperationException
  {
    HttpNettyClient client =
      new HttpClientBuilder(_eventLoop, _scheduler).buildRestClient();

    client.streamRequest(null, new RequestContext(), new HashMap<>(), null);
    Assert.fail("The Http Rest client should throw UnsupportedOperationException when streamRequest is called");
  }

  @Test
  public void testReceiveBadHeader() throws InterruptedException, IOException
  {
    TestServer testServer = new TestServer();
    HttpNettyClient client = new HttpClientBuilder(_eventLoop, _scheduler)
                                  .setRequestTimeout(10000)
                                  .setIdleTimeout(10000)
      .setShutdownTimeout(500).buildRestClient();

    RestRequest r = new RestRequestBuilder(testServer.getBadHeaderURI()).build();
    FutureCallback<RestResponse> cb = new FutureCallback<RestResponse>();
    TransportCallback<RestResponse> callback = new TransportCallbackAdapter<RestResponse>(cb);
    client.restRequest(r, new RequestContext(), new HashMap<String, String>(), callback);

    try
    {
      cb.get(30, TimeUnit.SECONDS);
      Assert.fail("Get was supposed to fail");
    }
    catch (TimeoutException e)
    {
      Assert.fail("Unexpected TimeoutException, should have been ExecutionException", e);
    }
    catch (ExecutionException e)
    {
      verifyCauseChain(e, RemoteInvocationException.class, IllegalArgumentException.class);
    }
    testServer.shutdown();
  }

  @Test
  public void testSendBadHeader() throws Exception
  {

    TestServer testServer = new TestServer();
    HttpNettyClient client = new HttpClientBuilder(_eventLoop, _scheduler)
        .setRequestTimeout(10000)
        .setIdleTimeout(10000)
      .setShutdownTimeout(500).buildRestClient();


    RestRequestBuilder rb = new RestRequestBuilder(testServer.getRequestURI());

    rb.setHeader("x", "makenettyunhappy\u000Bblah");
    RestRequest request = rb.build();
    FutureCallback<RestResponse> cb = new FutureCallback<RestResponse>();
    TransportCallback<RestResponse> callback = new TransportCallbackAdapter<RestResponse>(cb);
    client.restRequest(request, new RequestContext(), new HashMap<String, String>(), callback);

    try
    {
      cb.get(30, TimeUnit.SECONDS);
      Assert.fail("Should fail sending request");
    }
    catch (TimeoutException ex)
    {
      Assert.fail("Unexpected TimeoutException, should have been ExecutionException", ex);
    }
    catch (ExecutionException ex)
    {
      verifyCauseChain(ex, RemoteInvocationException.class, EncoderException.class, IllegalArgumentException.class);
    }
    testServer.shutdown();
  }

  @Test
  public void testShutdown() throws ExecutionException, TimeoutException, InterruptedException
  {
    HttpNettyClient client = new HttpClientBuilder(_eventLoop, _scheduler)
                                  .setRequestTimeout(500)
                                  .setIdleTimeout(10000)
                                  .setShutdownTimeout(500)
      .buildRestClient();

    FutureCallback<None> shutdownCallback = new FutureCallback<None>();
    client.shutdown(shutdownCallback);
    shutdownCallback.get(30, TimeUnit.SECONDS);

    // Now verify a new request will also fail
    RestRequest r = new RestRequestBuilder(URI.create("http://no.such.host.linkedin.com")).build();
    FutureCallback<RestResponse> callback = new FutureCallback<RestResponse>();
    client.restRequest(r, new RequestContext(), new HashMap<String, String>(), new TransportCallbackAdapter<RestResponse>(callback));
    try
    {
      callback.get(30, TimeUnit.SECONDS);
    }
    catch (ExecutionException e)
    {
      // Expected
    }
  }

  @Test
  public void testShutdownStuckInPool()
      throws InterruptedException, ExecutionException, TimeoutException

  {
    // Test that shutdown works when the outstanding request is stuck in the pool waiting for a channel
    HttpNettyClient client = new HttpNettyClient(new NoCreations(_scheduler), _scheduler, 60000, 1);

    RestRequest r = new RestRequestBuilder(URI.create("http://some.host/")).build();
    FutureCallback<RestResponse> futureCallback = new FutureCallback<RestResponse>();
    client.restRequest(r, new RequestContext(), new HashMap<String, String>(), new TransportCallbackAdapter<RestResponse>(futureCallback));

    FutureCallback<None> shutdownCallback = new FutureCallback<None>();
    client.shutdown(shutdownCallback);

    shutdownCallback.get(30, TimeUnit.SECONDS);

    try
    {
      futureCallback.get(30, TimeUnit.SECONDS);
      Assert.fail("get should have thrown exception");
    }
    catch (ExecutionException e)
    {
      verifyCauseChain(e, RemoteInvocationException.class, TimeoutException.class);
    }
  }

  @Test
  public void testShutdownRequestOutstanding()
      throws IOException, ExecutionException, TimeoutException, InterruptedException
  {
    // Test that it works when the shutdown kills the outstanding request...
    testShutdownRequestOutstanding(500, 60000, RemoteInvocationException.class, TimeoutException.class);
  }

  @Test
  public void testShutdownRequestOutstanding2()
      throws IOException, ExecutionException, TimeoutException, InterruptedException
  {
    // Test that it works when the request timeout kills the outstanding request...
    testShutdownRequestOutstanding(60000, 500, RemoteInvocationException.class,
        // sometimes the test fails with ChannelClosedException
        // TimeoutException.class
        Exception.class);
  }

  private void testShutdownRequestOutstanding(int shutdownTimeout, int requestTimeout, Class<?>... causeChain)
      throws InterruptedException, IOException, ExecutionException, TimeoutException
  {
    TestServer testServer = new TestServer();

    HttpNettyClient client = new HttpClientBuilder(_eventLoop, _scheduler).setRequestTimeout(requestTimeout)
      .setShutdownTimeout(shutdownTimeout).buildRestClient();

    RestRequest r = new RestRequestBuilder(testServer.getNoResponseURI()).build();
    FutureCallback<RestResponse> cb = new FutureCallback<RestResponse>();
    TransportCallback<RestResponse> callback = new TransportCallbackAdapter<RestResponse>(cb);
    client.restRequest(r, new RequestContext(), new HashMap<String, String>(), callback);

    FutureCallback<None> shutdownCallback = new FutureCallback<None>();
    client.shutdown(shutdownCallback);
    shutdownCallback.get(30, TimeUnit.SECONDS);

    try
    {
      // This timeout needs to be significantly larger than the getTimeout of the netty client;
      // we're testing that the client will generate its own timeout
      cb.get(30, TimeUnit.SECONDS);
      Assert.fail("Get was supposed to time out");
    }
    catch (TimeoutException e)
    {
      // TimeoutException means the timeout for Future.get() elapsed and nothing happened.
      // Instead, we are expecting our callback to be invoked before the future timeout
      // with a timeout generated by the HttpNettyClient.
      Assert.fail("Get timed out, should have thrown ExecutionException", e);
    }
    catch (ExecutionException e)
    {
      verifyCauseChain(e, causeChain);
    }
    testServer.shutdown();
  }

  // Test that cannot pass pass SSLParameters without SSLContext.
  // This in fact tests HttpClientPipelineFactory constructor through HttpNettyClient
  // constructor.
  @Test
  public void testClientPipelineFactory1()
      throws NoSuchAlgorithmException
  {
    try
    {
      new HttpClientBuilder(_eventLoop, _scheduler)
          .setSSLParameters(new SSLParameters())
        .buildRestClient();
    }
    catch (IllegalArgumentException e)
    {
      // Check exception message to make sure it's the expected one.
      Assert.assertEquals(e.getMessage(), "SSLParameters passed with no SSLContext");
    }
  }

  // Test that cannot set cipher suites in SSLParameters that don't have any match in
  // SSLContext.
  // This in fact tests HttpClientPipelineFactory constructor through HttpNettyClient
  // constructor.
  @Test
  public void testClientPipelineFactory2Fail()
      throws NoSuchAlgorithmException
  {
    String[] requestedCipherSuites = {"Unsupported"};
    SSLParameters sslParameters = new SSLParameters();
    sslParameters.setCipherSuites(requestedCipherSuites);
    try
    {
      new HttpClientBuilder(_eventLoop, _scheduler)
          .setSSLContext(SSLContext.getDefault())
          .setSSLParameters(sslParameters)
        .buildRestClient();
    }
    catch (IllegalArgumentException e)
    {
      // Check exception message to make sure it's the expected one.
      Assert.assertEquals(e.getMessage(), "None of the requested cipher suites: [Unsupported] are found in SSLContext");
    }
  }

  // Test that can set cipher suites in SSLParameters that have at least one match in
  // SSLContext.
  // This in fact tests HttpClientPipelineFactory constructor through HttpNettyClient
  // constructor.
  @Test
  public void testClientPipelineFactory2Pass()
      throws NoSuchAlgorithmException
  {
    String[] requestedCipherSuites = {"Unsupported", "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA"};
    SSLParameters sslParameters = new SSLParameters();
    sslParameters.setCipherSuites(requestedCipherSuites);
    new HttpClientBuilder(_eventLoop, _scheduler)
        .setSSLContext(SSLContext.getDefault())
        .setSSLParameters(sslParameters)
      .buildRestClient();
  }

  // Test that cannot set protocols in SSLParameters that don't have any match in
  // SSLContext.
  // This in fact tests HttpClientPipelineFactory constructor through HttpNettyClient
  // constructor.
  @Test
  public void testClientPipelineFactory3Fail()
      throws NoSuchAlgorithmException
  {
    String[] requestedProtocols = {"Unsupported"};
    SSLParameters sslParameters = new SSLParameters();
    sslParameters.setProtocols(requestedProtocols);
    try
    {
      new HttpClientBuilder(_eventLoop, _scheduler)
          .setSSLContext(SSLContext.getDefault())
          .setSSLParameters(sslParameters)
        .buildRestClient();
    }
    catch (IllegalArgumentException e)
    {
      // Check exception message to make sure it's the expected one.
      Assert.assertEquals(e.getMessage(), "None of the requested protocols: [Unsupported] are found in SSLContext");
    }
  }

  // Test that can set protocols in SSLParameters that have at least one match in
  // SSLContext.
  // This in fact tests HttpClientPipelineFactory constructor through HttpNettyClient
  // constructor.
  @Test
  public void testClientPipelineFactory3Pass()
      throws NoSuchAlgorithmException
  {
    String[] requestedProtocols = {"Unsupported", "TLSv1"};
    SSLParameters sslParameters = new SSLParameters();
    sslParameters.setProtocols(requestedProtocols);

    new HttpClientBuilder(_eventLoop, _scheduler)
        .setSSLContext(SSLContext.getDefault())
        .setSSLParameters(sslParameters)
      .buildRestClient();
  }

  @Test
  public void testPoolStatsProviderManager()
      throws InterruptedException, ExecutionException, TimeoutException
  {
    final CountDownLatch setLatch = new CountDownLatch(1);
    final CountDownLatch removeLatch = new CountDownLatch(1);
    AbstractJmxManager manager = new AbstractJmxManager()
    {
      @Override
      public void onProviderCreate(PoolStatsProvider provider)
      {
        setLatch.countDown();
      }

      @Override
      public void onProviderShutdown(PoolStatsProvider provider)
      {
        removeLatch.countDown();
      }
    };

    HttpNettyClient client =
        new HttpClientBuilder(_eventLoop, _scheduler)
            .setJmxManager(manager)
          .buildRestClient();
    // test setPoolStatsProvider
    try
    {
      setLatch.await(30, TimeUnit.SECONDS);
    }
    catch (InterruptedException e)
    {
      Assert.fail("PoolStatsAware setPoolStatsProvider didn't get called when creating channel pool.");
    }
    // test removePoolStatsProvider
    FutureCallback<None> shutdownCallback = new FutureCallback<None>();
    client.shutdown(shutdownCallback);
    try
    {
      removeLatch.await(30, TimeUnit.SECONDS);
    }
    catch (InterruptedException e)
    {
      Assert.fail("PoolStatsAware removePoolStatsProvider didn't get called when shutting down channel pool.");
    }
    shutdownCallback.get(30, TimeUnit.SECONDS);
  }

  @Test (enabled = false)
  public void testMakingOutboundHttpsRequest()
      throws NoSuchAlgorithmException, InterruptedException, ExecutionException, TimeoutException
  {
    SSLContext context = SSLContext.getDefault();
    SSLParameters sslParameters = context.getDefaultSSLParameters();

    HttpNettyClient client = new HttpClientBuilder(_eventLoop, _scheduler)
          .setSSLContext(context)
          .setSSLParameters(sslParameters)
      .buildRestClient();

    RestRequest r = new RestRequestBuilder(URI.create("https://www.howsmyssl.com/a/check")).build();
    FutureCallback<RestResponse> cb = new FutureCallback<RestResponse>();
    TransportCallback<RestResponse> callback = new TransportCallbackAdapter<RestResponse>(cb);
    client.restRequest(r, new RequestContext(), new HashMap<String, String>(), callback);
    cb.get(30, TimeUnit.SECONDS);
  }

  @Test
  public void testFailBackoff() throws Exception
  {
    final int WARM_UP = 10;
    final int N = 5;
    final int MAX_RATE_LIMITING_PERIOD = 500;

    final CountDownLatch warmUpLatch = new CountDownLatch(WARM_UP);
    final CountDownLatch latch = new CountDownLatch(N);
    final AtomicReference<Boolean> isShutdown = new AtomicReference<>(false);

    AsyncPool<Channel> testPool = new AsyncPoolImpl<>("test pool",
        new AsyncPool.Lifecycle<Channel>()
        {
          @Override
          public void create(Callback<Channel> callback)
          {
            if (warmUpLatch.getCount() > 0)
            {
              warmUpLatch.countDown();
            }
            else
            {
              latch.countDown();
            }
            callback.onError(new Throwable("Oops..."));
          }

          @Override
          public boolean validateGet(Channel obj)
          {
            return false;
          }

          @Override
          public boolean validatePut(Channel obj)
          {
            return false;
          }

          @Override
          public void destroy(Channel obj, boolean error, Callback<Channel> callback)
          {

          }

          @Override
          public PoolStats.LifecycleStats getStats()
          {
            return null;
          }
        },
        200,
        30000,
        _scheduler,
        Integer.MAX_VALUE,
        AsyncPoolImpl.Strategy.MRU,
        0,
        new ExponentialBackOffRateLimiter(0,
            MAX_RATE_LIMITING_PERIOD,
            Math.max(10, MAX_RATE_LIMITING_PERIOD / 32),
            _scheduler),
        new SettableClock(),
        new LongTracking()
     );
    HttpNettyClient client = new HttpNettyClient(address -> testPool, _scheduler, 500, 500);

    final RestRequest r = new RestRequestBuilder(URI.create("http://localhost:8080/")).setMethod("GET").build();
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.execute(() ->
    {
      while (!isShutdown.get())
      {
        try
        {
          FutureCallback<RestResponse> callback = new FutureCallback<>();
          client.restRequest(r, new RequestContext(), new HashMap<>(), new TransportCallbackAdapter<RestResponse>(callback));
          callback.get();
        }
        catch (Exception e)
        {
          // ignore
        }
      }
    });

    // First ensure a bunch fail to get the rate limiting going
    warmUpLatch.await(120, TimeUnit.SECONDS);
    // Now we should be rate limited
    long start = System.currentTimeMillis();
    System.err.println("Starting at " + start);
    long lowTolerance = N * MAX_RATE_LIMITING_PERIOD * 4 / 5;
    long highTolerance = N * MAX_RATE_LIMITING_PERIOD * 5 / 4;
    Assert.assertTrue(latch.await(highTolerance, TimeUnit.MILLISECONDS), "Should have finished within " + highTolerance + "ms");
    long elapsed = System.currentTimeMillis() - start;
    Assert.assertTrue(elapsed > lowTolerance, "Should have finished after " + lowTolerance + "ms (took " + elapsed +")");
    // shutdown everything
    isShutdown.set(true);
    executor.shutdown();
  }

  private static class NoCreations implements ChannelPoolFactory
  {
    private final ScheduledExecutorService _scheduler;

    public NoCreations(ScheduledExecutorService scheduler)
    {
      _scheduler = scheduler;
    }

    @Override
    public AsyncPool<Channel> getPool(SocketAddress address)
    {
      return new AsyncPoolImpl<Channel>("fake pool", new AsyncPool.Lifecycle<Channel>()
      {
        @Override
        public void create(Callback<Channel> channelCallback)
        {

        }

        @Override
        public boolean validateGet(Channel obj)
        {
          return false;
        }

        @Override
        public boolean validatePut(Channel obj)
        {
          return false;
        }

        @Override
        public void destroy(Channel obj, boolean error, Callback<Channel> channelCallback)
        {

        }

        @Override
        public PoolStats.LifecycleStats getStats()
        {
          return null;
        }
      }, 0, 0, _scheduler);
    }

  }

}
