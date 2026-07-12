import { createContext, useCallback, useContext, useEffect, useReducer, useRef, type ReactNode } from 'react';
import { api } from '../api/client';
import { BoetSocket, type ChangeMessage, type PresenceMember } from '../api/ws';
import { getIdentity, getMemberId, displayName } from './identity';
import { useAuth } from './auth';
import type { Bootstrap, BoetList, Category, Item, Favorite, Recipe, Member } from '../api/types';

interface State {
  loaded: boolean;
  members: Member[];
  lists: BoetList[];
  categories: Category[];
  items: Item[];
  favorites: Favorite[];
  recipes: Recipe[];
  presence: PresenceMember[];
}

const initialState: State = {
  loaded: false,
  members: [],
  lists: [],
  categories: [],
  items: [],
  favorites: [],
  recipes: [],
  presence: [],
};

type Action =
  | { type: 'bootstrap'; payload: Bootstrap }
  | { type: 'recipes'; payload: Recipe[] }
  | { type: 'presence'; members: PresenceMember[] }
  | { type: 'change'; msg: ChangeMessage };

function upsertBy<T extends { id: string }>(list: T[], item: T): T[] {
  const idx = list.findIndex((x) => x.id === item.id);
  if (idx === -1) return [...list, item];
  const copy = list.slice();
  copy[idx] = item;
  return copy;
}

function removeBy<T extends { id: string }>(list: T[], id: string): T[] {
  return list.filter((x) => x.id !== id);
}

function reorderBy<T extends { id: string; position: number }>(list: T[], order: string[]): T[] {
  const posOf = new Map(order.map((id, i) => [id, i]));
  return list.map((x) => (posOf.has(x.id) ? { ...x, position: posOf.get(x.id)! } : x));
}

// `data` on WS change payloads is server-shaped JSON — typed loosely here and
// cast to the right shape per entity below.
function applyChange(state: State, msg: ChangeMessage): State {
  const { event, entity, data } = msg;
  const d = data as Record<string, any>;
  switch (entity) {
    case 'list': {
      if (event === 'create' || event === 'update') {
        const lists = upsertBy(state.lists, d as BoetList);
        if (Array.isArray(d.categories)) {
          let categories = state.categories;
          for (const c of d.categories) categories = upsertBy(categories, c as Category);
          return { ...state, lists, categories };
        }
        return { ...state, lists };
      }
      if (event === 'delete') return { ...state, lists: removeBy(state.lists, d.id) };
      if (event === 'reorder') return { ...state, lists: reorderBy(state.lists, d.order) };
      return state;
    }
    case 'category': {
      if (event === 'create' || event === 'update') return { ...state, categories: upsertBy(state.categories, d as Category) };
      if (event === 'delete') return { ...state, categories: removeBy(state.categories, d.id) };
      if (event === 'reorder') return { ...state, categories: reorderBy(state.categories, d.order) };
      return state;
    }
    case 'item': {
      if (event === 'create' || event === 'update') return { ...state, items: upsertBy(state.items, d as Item) };
      if (event === 'delete') return { ...state, items: removeBy(state.items, d.id) };
      if (event === 'reorder') return { ...state, items: reorderBy(state.items, d.order) };
      if (event === 'bulk-delete') {
        const ids = new Set<string>(d.ids as string[]);
        return { ...state, items: state.items.filter((i) => !ids.has(i.id)) };
      }
      return state;
    }
    case 'favorite': {
      if (event === 'create' || event === 'update') return { ...state, favorites: upsertBy(state.favorites, d as Favorite) };
      if (event === 'delete') return { ...state, favorites: removeBy(state.favorites, d.id) };
      return state;
    }
    case 'recipe': {
      if (event === 'create' || event === 'update') return { ...state, recipes: upsertBy(state.recipes, d as Recipe) };
      if (event === 'delete') return { ...state, recipes: removeBy(state.recipes, d.id) };
      return state;
    }
    default:
      return state;
  }
}

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'bootstrap':
      return {
        ...state,
        loaded: true,
        members: action.payload.members,
        lists: action.payload.lists,
        categories: action.payload.categories,
        items: action.payload.items,
        favorites: action.payload.favorites,
        recipes: action.payload.recipes,
      };
    case 'recipes':
      return { ...state, loaded: true, recipes: action.payload };
    case 'presence':
      return { ...state, presence: action.members };
    case 'change':
      return applyChange(state, action.msg);
    default:
      return state;
  }
}

interface StoreValue extends State {
  refresh: () => Promise<void>;
  sendPresence: (status: 'viewing' | 'shopping', listId: string | null) => void;
}

const StoreContext = createContext<StoreValue | null>(null);

export function BoetStoreProvider({ children }: { children: ReactNode }) {
  const { authenticated } = useAuth();
  const [state, dispatch] = useReducer(reducer, initialState);
  const socketRef = useRef<BoetSocket | null>(null);

  const refresh = useCallback(async () => {
    if (!authenticated) {
      // Anonymous visitors only get the public read-only recipe list.
      const recipes = await api.get<Recipe[]>('/api/recipes');
      dispatch({ type: 'recipes', payload: recipes });
      return;
    }
    const payload = await api.get<Bootstrap>('/api/bootstrap');
    dispatch({ type: 'bootstrap', payload });
  }, [authenticated]);

  useEffect(() => {
    if (!authenticated) {
      // No WebSocket, no presence — a single snapshot fetch.
      refresh();
      return;
    }
    const identity = getIdentity();
    if (!identity) return;
    refresh();

    const socket = new BoetSocket(getMemberId(), displayName(identity), {
      onChange: (msg) => dispatch({ type: 'change', msg }),
      onPresence: (members) => dispatch({ type: 'presence', members }),
      // WS only carries live deltas — any reconnect after the first may have
      // missed changes while disconnected, so re-fetch the whole snapshot.
      onReconnect: () => {
        refresh();
      },
    });
    socket.connect();
    socketRef.current = socket;
    return () => socket.disconnect();
  }, [refresh, authenticated]);

  const sendPresence = useCallback((status: 'viewing' | 'shopping', listId: string | null) => {
    socketRef.current?.sendPresence(status, listId);
  }, []);

  return <StoreContext.Provider value={{ ...state, refresh, sendPresence }}>{children}</StoreContext.Provider>;
}

export function useBoetStore() {
  const ctx = useContext(StoreContext);
  if (!ctx) throw new Error('useBoetStore must be used within BoetStoreProvider');
  return ctx;
}
