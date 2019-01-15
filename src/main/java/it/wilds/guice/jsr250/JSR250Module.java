/**
 * Copyright (C) 2011 Brian Ferris <bdferris@onebusaway.org>
 * Copyright (C) 2012 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package it.wilds.guice.jsr250;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import com.google.inject.matcher.Matchers;
import com.google.inject.spi.InjectionListener;
import com.google.inject.spi.TypeEncounter;
import com.google.inject.spi.TypeListener;

/**
 * Adds support to Guice for JSR520 annotations, including {@link PostConstruct}
 * and {@link PreDestroy}.
 * 
 * @author bdferris
 */
public class JSR250Module extends AbstractModule {

  protected final static Logger _log = Logger.getLogger(JSR250Module.class.getSimpleName());

  public static void addModuleAndDependencies(Set<Module> modules) {
    modules.add(new JSR250Module());
  }

  @Override
  protected void configure() {

    final List<ObjectAndMethod> postConstructActions = new ArrayList<ObjectAndMethod>();
    final List<ObjectAndMethod> preDestroyActions = new ArrayList<ObjectAndMethod>();

    bindListener(Matchers.any(), new TypeListener() {

      @Override
      public <I> void hear(TypeLiteral<I> injectableType,
          TypeEncounter<I> encounter) {

        Class<? super I> type = injectableType.getRawType();
        while (type != null) {
          Method[] methods = type.getDeclaredMethods();

          for (final Method method : methods) {

            PostConstruct postConstruct = method.getAnnotation(PostConstruct.class);
            if (postConstruct != null)
              encounter.register(new RegisterMethodCallback<I>(
                  postConstructActions, method));

            PreDestroy preDestory = method.getAnnotation(PreDestroy.class);
            if (preDestory != null)
              encounter.register(new RegisterMethodCallback<I>(
                  preDestroyActions, method));
          }
          
          type = type.getSuperclass();
        }
      }
    });

    LifecycleServiceImpl service = new LifecycleServiceImpl(
        postConstructActions, preDestroyActions);
    bind(LifecycleService.class).toInstance(service);

    Runtime runtime = Runtime.getRuntime();
    PreDestroyShutdownHook hook = new PreDestroyShutdownHook(service);
    runtime.addShutdownHook(new Thread(hook));
  }

  /**
   * Implement hashCode() and equals() such that two instances of the module
   * will be equal.
   */
  @Override
  public int hashCode() {
    return this.getClass().hashCode();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null)
      return false;
    return this.getClass().equals(o.getClass());
  }

  private static class ObjectAndMethod {
    private final Object object;

    private final Method method;

    private boolean hasBeenRun = false;

    public ObjectAndMethod(Object object, Method method) {
      this.object = object;
      this.method = method;
    }

    public synchronized void reset() {
      hasBeenRun = false;
    }

    public void execute() {

      synchronized (this) {
        if (hasBeenRun)
          return;
        hasBeenRun = true;
      }

      try {
        method.setAccessible(true);
        method.invoke(object);
      } catch (Throwable ex) {
        try {
          _log.warning("error invoking method " + method + " on target " + object + ": " + ex.getMessage());
        } catch (Exception ex2) {
          System.err.println("error invoking method " + method + " on target " + object);
          ex.printStackTrace();
        }
      }
    }

    @Override
    public String toString() {
      return object + " " + method;
    }
  }

  private static class RegisterMethodCallback<I> implements
      InjectionListener<I> {

    private final List<ObjectAndMethod> _actions;

    private final Method _method;

    public RegisterMethodCallback(List<ObjectAndMethod> preDestroyActions,
        Method method) {
      _actions = preDestroyActions;
      _method = method;
    }

    @Override
    public void afterInjection(I injectee) {
      _actions.add(new ObjectAndMethod(injectee, _method));
    }
  }

  private static class LifecycleServiceImpl implements LifecycleService {

    private final List<ObjectAndMethod> _postConstructActions;
    private final List<ObjectAndMethod> _preDestroyActions;

    private boolean _started = false;

    public LifecycleServiceImpl(List<ObjectAndMethod> postConstructActions,
        List<ObjectAndMethod> preDestroyActions) {
      _postConstructActions = postConstructActions;
      _preDestroyActions = preDestroyActions;
    }

    @Override
    public synchronized void start() {
      if (_started)
        return;
      _started = true;

      for (ObjectAndMethod target : _postConstructActions) {
        target.execute();
      }

      /**
       * Reset pre-destory methods, making it possible to call stop() again.
       */
      for (ObjectAndMethod target : _preDestroyActions) {
        target.reset();
      }
    }

    @Override
    public void stop() {
      if (!_started)
        return;
      _started = false;

      /**
       * The @PreDestroy actions need to be applied in reverse order of bean
       * instantiation
       */
      for (int i = _preDestroyActions.size() - 1; i >= 0; --i) {
        ObjectAndMethod target = _preDestroyActions.get(i);
        target.execute();
      }

      /**
       * Reset post-construct methods, making it possible to call start() again.
       */
      for (ObjectAndMethod target : _postConstructActions) {
        target.reset();
      }
    }
  }

  private static class PreDestroyShutdownHook implements Runnable {

    private LifecycleService _service;

    public PreDestroyShutdownHook(LifecycleService service) {
      _service = service;
    }

    @Override
    public void run() {
      _service.stop();
    }
  }
}
