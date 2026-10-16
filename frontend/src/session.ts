/**
 * Ao abrir o painel, "quem está logado?" pode falhar por dois motivos bem diferentes: a sessão acabou (401/403, vai
 * para o login) ou o servidor ainda está acordando/fora do ar (502/503/504, rede). No segundo caso a sessão continua
 * válida no banco: o painel espera e tenta de novo em vez de mandar a pessoa para o login.
 */
export function meFailure(status: number | undefined): 'logged-out' | 'unavailable' {
  return status === 401 || status === 403 ? 'logged-out' : 'unavailable';
}

/** Espera entre as tentativas enquanto o servidor acorda: 3 s, 5 s e depois a cada 8 s. */
export function wakeDelay(attempt: number): number {
  return attempt === 0 ? 3000 : attempt === 1 ? 5000 : 8000;
}
