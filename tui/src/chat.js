import {randomUUID} from 'node:crypto';
import {useEffect, useRef, useState} from 'react';
import {ApiError} from './core.js';

const historyLimit = 100;
const conversationPath = (id) => `/conversations/${encodeURIComponent(id)}`;
const agentPath = (id) => `/agents/${encodeURIComponent(id)}`;

export async function modelFor(api, agent, conversation) {
  const id = conversation?.override_model_id ?? agent.default_model_id;
  if (!id) return null;
  try {
    return await api.json(`/models/${encodeURIComponent(id)}`);
  } catch (error) {
    if (error.code === 'not_found') return null;
    throw error;
  }
}

export function errorText(error) {
  return error.code ? `${error.code}: ${error.message}` : error.message;
}

export function useChat(api, initialAgent, initialModel) {
  const [agent, setAgent] = useState(initialAgent);
  const [model, setModel] = useState(initialModel);
  const [session, setSession] = useState(null);
  const [messages, setMessages] = useState([]);
  const [hasOlder, setHasOlder] = useState(false);
  const [live, setLive] = useState(null);
  const [fault, setFault] = useState('');
  const [notice, setNotice] = useState(null);
  const [busy, setBusy] = useState(false);
  const locked = useRef(false);
  const active = useRef(null);

  useEffect(() => () => active.current?.controller.abort(), []);

  function status(text, error = false) {
    setNotice({text, error});
  }

  async function perform(task) {
    if (locked.current) return;
    locked.current = true;
    setBusy(true);
    setNotice(null);
    try {
      await task();
    } catch (error) {
      status(errorText(error), true);
    } finally {
      locked.current = false;
      setBusy(false);
    }
  }

  async function newSession(nextAgent = agent) {
    const nextModel = await modelFor(api, nextAgent);
    setAgent(nextAgent);
    setModel(nextModel);
    setSession(null);
    setMessages([]);
    setHasOlder(false);
    setLive(null);
    setFault('');
  }

  async function openSession(selected) {
    const path = conversationPath(selected.id);
    const [conversation, history] = await Promise.all([
      api.json(path),
      api.json(`${path}/messages?limit=${historyLimit}`),
    ]);
    const owner = await api.json(agentPath(conversation.agent_id));
    const nextModel = await modelFor(api, owner, conversation);
    setAgent(owner);
    setModel(nextModel);
    applyHistory(conversation, history);
    setFault('');
  }

  function applyHistory(conversation, history) {
    setSession(conversation);
    setMessages(history.items);
    setHasOlder(history.items.length < history.total);
    setLive(null);
  }

  async function olderMessages() {
    if (!hasOlder || !messages.length) return;
    const before = encodeURIComponent(messages[0].id);
    const path = conversationPath(session.id);
    const page = await api.json(`${path}/messages?limit=${historyLimit}&beforeId=${before}`);
    setMessages([...page.items, ...messages]);
    setHasOlder(page.items.length > 0 && page.items.length + messages.length < page.total);
  }

  async function chooseModel(selected) {
    if (session) {
      const path = conversationPath(session.id);
      const current = await api.json(path);
      const updated = await api.json(path, {method: 'PUT', body: {
        ...current, provider_id: selected.provider_id,
        override_model_id: selected.id, updated_at: Date.now(),
      }});
      setSession(updated);
    } else {
      const path = agentPath(agent.id);
      const current = await api.json(path);
      const updated = await api.json(path, {method: 'PUT', body: {
        ...current, default_model_id: selected.id, updated_at: Date.now(),
      }});
      setAgent(updated);
    }
    setModel(selected);
    status(`Model set to ${selected.display_name}.`);
  }

  async function createSession(content) {
    const now = Date.now();
    const title = Array.from(content.trim().split(/\r?\n/, 1)[0]).slice(0, 64).join('');
    const conversation = await api.json('/conversations', {method: 'PUT', body: {
      id: randomUUID(), title, provider_id: model.provider_id, agent_id: agent.id,
      created_at: now, updated_at: now,
    }});
    setSession(conversation);
    return conversation;
  }

  async function turn(body, onAccepted = () => {}) {
    const request = {controller: new AbortController(), path: null, started: false};
    active.current = request;
    setFault('');
    setLive(null);
    let terminalError;
    let refreshError;
    try {
      if (!model) throw new ApiError('model_required', 'Choose a model with /models or add one with /connect.');
      const conversation = session ?? await createSession(body.content);
      request.path = conversationPath(conversation.id);
      request.controller.signal.throwIfAborted();
      status('Waiting for the model…');
      for await (const event of api.turn(request.path, body, request.controller.signal)) {
        if (!request.started) {
          request.started = true;
          if (body.operation === 'retry') {
            setMessages((items) => items.filter((item) => item.id !== body.messageId));
          } else if (body.operation === 'regenerate') {
            setMessages((items) => {
              const start = items.findIndex((item) => item.id === body.messageId);
              if (start === -1) return items;
              return items.filter((item, index) =>
                index < start || (item.role !== 'assistant' && item.role !== 'tool'));
            });
          }
          setLive({question: body.content, content: '', round: null});
          onAccepted();
        }
        switch (event.type) {
          case 'TextDelta':
            setLive((previous) => ({
              ...previous,
              content: previous.content + (previous.round !== null && previous.round !== event.round
                ? '\n\n' : '') + event.text,
              round: event.round,
            }));
            break;
          case 'ToolCallStarted':
            status(`Tool · ${event.name}`);
            break;
          case 'ToolCallRequested':
            await api.json(`/turns/tools/${encodeURIComponent(event.callId)}/result`, {
              method: 'POST',
              body: {output: 'Local tool execution is unavailable in the terminal client.', isError: true},
            });
            break;
          case 'ToolCallFinished':
            status(event.isError ? `Tool failed · ${event.name}` : 'Waiting for the model…', event.isError);
            break;
          case 'Error':
            terminalError = new ApiError(event.code, event.message);
            break;
          case 'Cancelled':
            status('Turn cancelled.');
            break;
          case 'Finished':
            setNotice(null);
            break;
        }
      }
    } catch (error) {
      if (request.controller.signal.aborted) status('Turn cancelled.');
      else terminalError ??= error;
      // Also disconnect if a client-side exception interrupted an active turn.
      request.controller.abort();
    } finally {
      if (request.path) {
        try {
          const [conversation, history] = await Promise.all([
            api.json(request.path),
            api.json(`${request.path}/messages?limit=${historyLimit}`),
          ]);
          applyHistory(conversation, history);
          if (terminalError && history.items.at(-1)?.error_message === terminalError.message) {
            setFault('');
          } else if (terminalError) {
            setFault(errorText(terminalError));
          }
        } catch (error) {
          refreshError = error;
          if (terminalError) setFault(errorText(terminalError));
        }
      } else if (terminalError) {
        setFault(errorText(terminalError));
      }
      if (terminalError || refreshError) {
        status([terminalError, refreshError].filter(Boolean).map(errorText).join(' · '), true);
      }
      active.current = null;
    }
  }

  async function cancel() {
    const request = active.current;
    if (!request) return;
    if (!request.started) {
      request.controller.abort();
      return;
    }
    try {
      await api.json(`${request.path}/cancel`, {method: 'POST'});
    } catch (error) {
      if (active.current === request) status(errorText(error), true);
    }
  }

  return {
    agent, model, session, messages, hasOlder, live, fault, notice, busy,
    perform, status, newSession, openSession, olderMessages, chooseModel, turn, cancel,
  };
}
