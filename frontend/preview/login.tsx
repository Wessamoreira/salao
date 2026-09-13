/*
 * Prévia da entrada, sem backend.
 *
 *   npm run previa:login   →   http://localhost:5174/preview/login.html
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { Login } from '../src/telas/Login';
import '../src/estilo/global.css';

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <MemoryRouter>
      <Login />
    </MemoryRouter>
  </StrictMode>,
);
