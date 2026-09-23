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

/*
 * O servidor falso recusa a senha como o de verdade recusa: 401 com o código estável. É o
 * caso do dia a dia — e é o que mostra o campo marcado e o foco voltando para a senha.
 * Qualquer senha que comece com "certa" entra.
 */
window.fetch = (async (_entrada: RequestInfo | URL, init?: RequestInit) => {
  const enviado = JSON.parse(String(init?.body ?? '{}')) as { senha?: string };
  await new Promise((resolve) => setTimeout(resolve, 400));
  if (!enviado.senha?.startsWith('certa')) {
    return new Response(JSON.stringify({
      type: 'https://salao.app/erros/ER-IAM-CREDENCIAIS_INVALIDAS', status: 401,
      title: 'Credenciais inválidas', codigo: 'ER-IAM-CREDENCIAIS_INVALIDAS',
      detail: 'e-mail ou senha inválidos',
    }), { status: 401, headers: { 'Content-Type': 'application/problem+json' } });
  }
  return new Response(JSON.stringify({ segundoFatorNecessario: false, tokenDeAcesso: 'tk' }),
    { status: 200, headers: { 'Content-Type': 'application/json' } });
}) as unknown as typeof fetch;

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <MemoryRouter>
      <Login />
    </MemoryRouter>
  </StrictMode>,
);
