import { routes } from '../appRouter';
export function requiresAuth(path: string): boolean {
  return !['login','register'].includes(path);
}
export function guard(path: string): string | null {
  if (requiresAuth(path) && !localStorage.getItem('jwt')) return 'login';
  return null;
}
export const _routes = routes;
