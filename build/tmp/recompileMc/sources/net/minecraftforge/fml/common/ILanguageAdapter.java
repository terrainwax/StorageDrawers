/*
 * Minecraft Forge
 * Copyright (c) 2016.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fml.common;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.minecraftforge.fml.relauncher.Side;

import org.apache.logging.log4j.Level;

public interface ILanguageAdapter {
    public Object getNewInstance(FMLModContainer container, Class<?> objectClass, ClassLoader classLoader, Method factoryMarkedAnnotation) throws Exception;
    public boolean supportsStatics();
    public void setProxy(Field target, Class<?> proxyTarget, Object proxy) throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException;
    public void setInternalProxies(ModContainer mod, Side side, ClassLoader loader);

    public static class ScalaAdapter implements ILanguageAdapter {
        @Override
        public Object getNewInstance(FMLModContainer container, Class<?> scalaObjectClass, ClassLoader classLoader, Method factoryMarkedAnnotation) throws Exception
        {
            Class<?> sObjectClass = Class.forName(scalaObjectClass.getName()+"$",true,classLoader);
            return sObjectClass.getField("MODULE$").get(null);
        }

        @Override
        public boolean supportsStatics()
        {
            return false;
        }

        @Override
        public void setProxy(Field target, Class<?> proxyTarget, Object proxy) throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException
        {
            try
            {
                // Get the actual singleton class. The two variants are from
                // whether the @SidedProxy is declared in a the class block
                // of the object directly, or in the object block, i.e.
                // whether it's:
                // class ModName {
                //   @SidedProxy ...
                // }
                // object ModName extends ModName {}
                // which leads to us getting the outer class, or
                // object ModName {
                //   @SidedProxy ...
                // }
                // which leads to us getting the inner class.
                if (!proxyTarget.getName().endsWith("$"))
                {
                    // Get internal class generated by Scala.
                    proxyTarget = Class.forName(proxyTarget.getName() + "$", true, proxyTarget.getClassLoader());
                }
            }
            catch (ClassNotFoundException e)
            {
                // Not a singleton, look for @Instance field as a fallback.
                FMLLog.log(Level.INFO, e, "An error occurred trying to load a proxy into %s.%s. Did you declare your mod as 'class' instead of 'object'?", proxyTarget.getSimpleName(), target.getName());
                return;
            }

            // Get the instance via the MODULE$ field which is
            // automatically generated by the Scala compiler for
            // singletons.
            Object targetInstance = proxyTarget.getField("MODULE$").get(null);

            try
            {
                // Find setter function. We do it this way because we can't
                // necessarily use proxyTarget.getMethod(proxy.getClass()), as
                // it might be a subclass and not the exact parameter type.
                // All fields are private in Scala, wrapped by a getter and
                // setter named <fieldname> and <fieldname>_$eq. To those
                // familiar with scala.reflect.BeanProperty: these will always
                // be there, set<Fieldname> and get<Fieldname> will always
                // only be generated *additionally*.
                final String setterName = target.getName() + "_$eq";
                for (Method setter : proxyTarget.getMethods())
                {
                    Class<?>[] setterParameters = setter.getParameterTypes();
                    if (setterName.equals(setter.getName()) &&
                            // Some more validation.
                            setterParameters.length == 1 &&
                            setterParameters[0].isAssignableFrom(proxy.getClass()))
                    {
                        // Here goes nothing...
                        setter.invoke(targetInstance, proxy);
                        return;
                    }
                }
            }
            catch (InvocationTargetException e)
            {
                FMLLog.log(Level.ERROR, e, "An error occurred trying to load a proxy into %s.%s", proxyTarget.getSimpleName(), target.getName());
                throw new LoaderException(e);
            }

            // If we come here we could not find a setter for this proxy.
            FMLLog.severe("Failed loading proxy into %s.%s, could not find setter function. Did you declare the field with 'val' instead of 'var'?", proxyTarget.getSimpleName(), target.getName());
            throw new LoaderException(String.format("Failed loading proxy into %s.%s, could not find setter function. Did you declare the field with 'val' instead of 'var'?", proxyTarget.getSimpleName(), target.getName()));
        }

        @Override
        public void setInternalProxies(ModContainer mod, Side side, ClassLoader loader)
        {
            // For Scala mods, we want to enable authors to write them like so:
            // object ModName {
            //   @SidedProxy(...)
            //   var proxy: ModProxy = null
            // }
            // For this to work, we have to search inside the inner class Scala
            // generates for singletons, which is in called ModName$. These are
            // not automatically handled, because the mod discovery code ignores
            // internal classes.
            // Note that it is alternatively possible to write this like so:
            // class ModName {
            //   @SidedProxy(...)
            //   var proxy: ModProxy = null
            // }
            // object ModName extends ModName { ... }
            // which will fall back to the normal injection code which calls
            // setProxy in turn.

            // Get the actual mod implementation, which will be the inner class
            // if we have a singleton.
            Class<?> proxyTarget = mod.getMod().getClass();
            if (proxyTarget.getName().endsWith("$"))
            {
                // So we have a singleton class, check if there are targets.
                for (Field target : proxyTarget.getDeclaredFields())
                {
                    // This will not turn up anything if the alternative
                    // approach was taken (manually declaring the class).
                    // So we don't initialize the field twice.
                    if (target.getAnnotation(SidedProxy.class) != null)
                    {
                        String targetType = side.isClient() ? target.getAnnotation(SidedProxy.class).clientSide() : target.getAnnotation(SidedProxy.class).serverSide();
                        try
                        {
                            Object proxy = Class.forName(targetType, true, loader).newInstance();

                            if (!target.getType().isAssignableFrom(proxy.getClass()))
                            {
                                FMLLog.severe("Attempted to load a proxy type %s into %s.%s, but the types don't match", targetType, proxyTarget.getSimpleName(), target.getName());
                                throw new LoaderException(String.format("Attempted to load a proxy type %s into %s.%s, but the types don't match", targetType, proxyTarget.getSimpleName(), target.getName()));
                            }

                            setProxy(target, proxyTarget, proxy);
                        }
                        catch (Exception e) {
                            FMLLog.log(Level.ERROR, e, "An error occurred trying to load a proxy into %s.%s", proxyTarget.getSimpleName(), target.getName());
                            throw new LoaderException(e);
                        }
                    }
                }
            }
            else
            {
                FMLLog.finer("Mod does not appear to be a singleton.");
            }
        }
    }

    public static class JavaAdapter implements ILanguageAdapter {
        @Override
        public Object getNewInstance(FMLModContainer container, Class<?> objectClass, ClassLoader classLoader, Method factoryMarkedMethod) throws Exception
        {
            if (factoryMarkedMethod != null)
            {
                return factoryMarkedMethod.invoke(null);
            }
            else
            {
                return objectClass.newInstance();
            }
        }

        @Override
        public boolean supportsStatics()
        {
            return true;
        }

        @Override
        public void setProxy(Field target, Class<?> proxyTarget, Object proxy) throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException,
                SecurityException
        {
            target.set(null, proxy);
        }

        @Override
        public void setInternalProxies(ModContainer mod, Side side, ClassLoader loader)
        {
            // Nothing to do here.
        }
    }
}