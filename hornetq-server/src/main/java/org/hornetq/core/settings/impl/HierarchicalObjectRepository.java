/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.core.settings.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.hornetq.core.server.HornetQServerLogger;
import org.hornetq.core.settings.HierarchicalRepository;
import org.hornetq.core.settings.HierarchicalRepositoryChangeListener;
import org.hornetq.core.settings.Mergeable;

/**
 * allows objects to be mapped against a regex pattern and held in order in a list
 *
 * @author <a href="ataylor@redhat.com">Andy Taylor</a>
 * @author <a href="tim.fox@jboss.com">Tim Fox</a>
 */
public class HierarchicalObjectRepository<T> implements HierarchicalRepository<T>
{

   /**
    * The default Match to fall back to
    */
   private T defaultmatch;

   /**
    * all the matches
    */
   private final Map<String, Match<T>> matches = new HashMap<String, Match<T>>();

   /**
    * Certain values cannot be removed after installed.
    * This is because we read a few records from the main config.
    * JBoss AS deployer may remove them on undeploy, while we don't want to accept that since
    * this could cause issues on shutdown.
    * Notice you can still change these values. You just can't remove them.
    */
   private final Set<String> immutables = new HashSet<String>();

   /**
    * a regex comparator
    */
   private final MatchComparator matchComparator = new MatchComparator();

   /**
    * a cache
    */
   private final Map<String, T> cache = new ConcurrentHashMap<String, T>();

   /**
    * Need a lock instead of using multiple {@link ConcurrentHashMap}s.
    * <p>
    * We could have a race between the state of {@link #matches} and {@link #cache}:
    * <p>
    * Thread1: calls {@link #addMatch(String , T)}: i. cleans cache; ii. adds match to Map.<br/>
    * Thread2: could add an (out-dated) entry to the cache between 'i. clean cache' and 'ii. add
    * match to Map'.
    * <p>
    * The lock is OK with regards to performance because we can search the cache before entering the
    * lock.
    * <p>
    * The lock is required for the 'add match to cache' part.
    */
   private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(false);

   /**
    * any registered listeners, these get fired on changes to the repository
    */
   private final ArrayList<HierarchicalRepositoryChangeListener> listeners = new ArrayList<HierarchicalRepositoryChangeListener>();


   public void addMatch(final String match, final T value)
   {
      addMatch(match, value, false);
   }


   /**
    * Add a new match to the repository
    *
    * @param match The regex to use to match against
    * @param value the value to hold against the match
    */
   public void addMatch(final String match, final T value, final boolean immutableMatch)
   {
      lock.writeLock().lock();
      try
      {
         clearCache();

         if (immutableMatch)
         {
            immutables.add(match);
         }
         Match.verify(match);
         Match<T> match1 = new Match<T>(match);
         match1.setValue(value);
         matches.put(match, match1);
         onChange();
      }
      finally
      {
         lock.writeLock().unlock();
      }
   }

   public int getCacheSize()
   {
      return cache.size();
   }

   /**
    * return the value held against the nearest match
    *
    * @param match the match to look for
    * @return the value
    */
   public T getMatch(final String match)
   {
      T cacheResult = cache.get(match);
      if (cacheResult != null)
      {
         return cacheResult;
      }
      lock.readLock().lock();
      try
      {
         T actualMatch;
         Map<String, Match<T>> possibleMatches = getPossibleMatches(match);
         Collection<Match<T>> orderedMatches = sort(possibleMatches);
         actualMatch = merge(orderedMatches);
         T value = actualMatch != null ? actualMatch : defaultmatch;
         if (value != null)
         {
            cache.put(match, value);
         }
         return value;
      }
      finally
      {
         lock.readLock().unlock();
      }
   }

   /**
    * merge all the possible matches, if the values implement Mergeable then a full merge is done
    * @param orderedMatches
    * @return
    */
   private T merge(final Collection<Match<T>> orderedMatches)
   {
      T actualMatch = null;
      for (Match<T> match : orderedMatches)
      {
         if (actualMatch == null || !Mergeable.class.isAssignableFrom(actualMatch.getClass()))
         {
            actualMatch = match.getValue();
            if (!Mergeable.class.isAssignableFrom(actualMatch.getClass()))
            {
               break;
            }
         }
         else
         {
            ((Mergeable)actualMatch).merge(match.getValue());
         }
      }
      return actualMatch;
   }

   /**
    * Sort the matches according to their precedence (that is, according to the precedence of their
    * keys).
    * @param possibleMatches
    * @return
    */
   private List<Match<T>> sort(final Map<String, Match<T>> possibleMatches)
   {
      List<String> keys = new ArrayList<String>(possibleMatches.keySet());
      Collections.sort(keys, matchComparator);
      List<Match<T>> matches1 = new ArrayList<Match<T>>();
      for (String key : keys)
      {
         matches1.add(possibleMatches.get(key));
      }
      return matches1;
   }

   /**
    * remove a match from the repository
    *
    * @param match the match to remove
    */
   public void removeMatch(final String match)
   {
      lock.writeLock().lock();
      try
      {
         boolean isImmutable = immutables.contains(match);
         if (isImmutable)
         {
            HornetQServerLogger.LOGGER.debug("Cannot remove match " + match + " since it came from a main config");
         }
         else
         {
            /**
             * clear the cache before removing the match. This will force any thread at
             * {@link #getMatch(String)} to get the lock to recompute.
             */
            clearCache();
            matches.remove(match);
            onChange();
         }
      }
      finally
      {
         lock.writeLock().unlock();
      }
   }

   public void registerListener(final HierarchicalRepositoryChangeListener listener)
   {
      listeners.add(listener);
   }

   public void unRegisterListener(final HierarchicalRepositoryChangeListener listener)
   {
      listeners.remove(listener);
   }

   /**
    * set the default value to fallback to if none found
    *
    * @param defaultValue the value
    */
   public void setDefault(final T defaultValue)
   {
      clearCache();
      defaultmatch = defaultValue;
   }

   public void clear()
   {
      lock.writeLock().lock();
      try
      {
         clearCache();
         listeners.clear();
         matches.clear();
      }
      finally
      {
         lock.writeLock().unlock();
      }
   }

   public void clearListeners()
   {
      listeners.clear();
   }

   public void clearCache()
   {
      cache.clear();
   }

   private void onChange()
   {
      for (HierarchicalRepositoryChangeListener listener : listeners)
      {
         try
         {
            listener.onChange();
         }
         catch (Throwable e)
         {
            HornetQServerLogger.LOGGER.errorCallingRepoListener(e);
         }
      }
   }

   /**
    * return any possible matches
    * @param match
    * @return
    */
   private Map<String, Match<T>> getPossibleMatches(final String match)
   {
      HashMap<String, Match<T>> possibleMatches = new HashMap<String, Match<T>>();

      for (Entry<String, Match<T>> entry : matches.entrySet())
      {
         Match<T> entryMatch = entry.getValue();
         if (entryMatch.getPattern().matcher(match).matches())
         {
            possibleMatches.put(entry.getKey(), entryMatch);
         }
      }
      return possibleMatches;
   }

   /**
    * Compares to matches to see which one is more specific.
    */
   private static final class MatchComparator implements Comparator<String>, Serializable
   {
      private static final long serialVersionUID = -6182535107518999740L;

      public int compare(final String o1, final String o2)
      {
         if (o1.contains(Match.WILDCARD) && !o2.contains(Match.WILDCARD))
         {
            return +1;
         }
         else if (!o1.contains(Match.WILDCARD) && o2.contains(Match.WILDCARD))
         {
            return -1;
         }
         else if (o1.contains(Match.WILDCARD) && o2.contains(Match.WILDCARD))
         {
            return o2.length() - o1.length();
         }
         else if (o1.contains(Match.WORD_WILDCARD) && !o2.contains(Match.WORD_WILDCARD))
         {
            return +1;
         }
         else if (!o1.contains(Match.WORD_WILDCARD) && o2.contains(Match.WORD_WILDCARD))
         {
            return -1;
         }
         else if (o1.contains(Match.WORD_WILDCARD) && o2.contains(Match.WORD_WILDCARD))
         {
            String[] leftSplits = o1.split("\\.");
            String[] rightSplits = o2.split("\\.");
            for (int i = 0; i < leftSplits.length; i++)
            {
               String left = leftSplits[i];
               if (left.equals(Match.WORD_WILDCARD))
               {
                  if (rightSplits.length < i || !rightSplits[i].equals(Match.WORD_WILDCARD))
                  {
                     return -1;
                  }
                  else
                  {
                     return +1;
                  }
               }
            }
         }
         return o1.length() - o2.length();
      }
   }
}
