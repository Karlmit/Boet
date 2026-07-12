import { useEffect } from 'react';
import { BrowserRouter, Routes, Route, Outlet, Navigate, useLocation } from 'react-router-dom';
import { AuthProvider, useAuth } from './state/auth';
import AppShell from './components/AppShell';
import Welcome from './pages/Welcome';
import Home from './pages/Home';
import ListsPage from './pages/ListsPage';
import ListSettings from './pages/ListSettings';
import CategoryManage from './pages/CategoryManage';
import ShoppingMode from './pages/ShoppingMode';
import RecipesGrid from './pages/RecipesGrid';
import RecipeDetail from './pages/RecipeDetail';
import RecipeEditor from './pages/RecipeEditor';
import RecipeImportAi from './pages/RecipeImportAi';
import RecipeImportByUrl from './pages/RecipeImportByUrl';
import Discover from './pages/Discover';
import MealDetail from './pages/MealDetail';
import History from './pages/History';
import Settings from './pages/Settings';

// /login is a BFF-rendered page, not an SPA route — leave the router entirely.
function RequireAuth() {
  const { authenticated } = useAuth();
  const location = useLocation();

  useEffect(() => {
    if (!authenticated) {
      window.location.href = `/login?next=${encodeURIComponent(location.pathname)}`;
    }
  }, [authenticated, location.pathname]);

  if (!authenticated) return null;
  return <Outlet />;
}

function RootRoute() {
  const { authenticated } = useAuth();
  if (!authenticated) return <Navigate to="/recipes" replace />;
  return <Home />;
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route element={<RequireAuth />}>
            <Route path="/welcome" element={<Welcome />} />
          </Route>
          <Route element={<AppShell />}>
            {/* Public: recipe browsing is shareable with people outside the household. */}
            <Route path="/" element={<RootRoute />} />
            <Route path="/recipes" element={<RecipesGrid />} />
            <Route path="/recipes/:recipeId" element={<RecipeDetail />} />
            <Route element={<RequireAuth />}>
              <Route path="/lists" element={<ListsPage />} />
              <Route path="/lists/:listId/settings" element={<ListSettings />} />
              <Route path="/lists/:listId/categories" element={<CategoryManage />} />
              <Route path="/lists/:listId/shopping" element={<ShoppingMode />} />
              <Route path="/recipes/new" element={<RecipeEditor />} />
              <Route path="/recipes/new/ai" element={<RecipeImportAi />} />
              <Route
                path="/recipes/new/url"
                element={
                  <RecipeImportByUrl
                    title="Importera från länk"
                    description="Klistra in en länk till ett recept — vi hämtar och strukturerar det åt dig."
                    endpoint="/api/recipes/scrape-async"
                    placeholder="https://exempel.se/recept/..."
                  />
                }
              />
              <Route
                path="/recipes/new/instagram"
                element={
                  <RecipeImportByUrl
                    title="Importera från Instagram"
                    description="Klistra in länken till en Instagram Reel med ett recept."
                    endpoint="/api/recipes/instagram-async"
                    placeholder="https://www.instagram.com/reel/..."
                  />
                }
              />
              <Route path="/recipes/discover" element={<Discover />} />
              <Route path="/recipes/discover/meal/:mealId" element={<MealDetail />} />
              <Route path="/recipes/:recipeId/edit" element={<RecipeEditor />} />
              <Route path="/history" element={<History />} />
              <Route path="/settings" element={<Settings />} />
            </Route>
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
