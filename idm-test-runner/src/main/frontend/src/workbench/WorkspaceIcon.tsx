type WorkspaceIconName = 'collapse' | 'expand' | 'config' | 'scenarios' | 'new' | 'upload' | 'chevron';

interface Props {
  name: WorkspaceIconName;
}

/** Small outline symbols shared by Workspace rows, actions, and its narrow rail. */
export function WorkspaceIcon({ name }: Props) {
  if (name === 'collapse' || name === 'expand') {
    return (
      <svg className="workspace-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
        <rect x="3.2" y="4.2" width="17.6" height="15.6" rx="2.3" />
        <path d="M9 4.8v14.4" />
        <path d={name === 'collapse' ? 'm16 9-3 3 3 3' : 'm13 9 3 3-3 3'} />
      </svg>
    );
  }
  if (name === 'config' || name === 'scenarios') {
    return (
      <svg className="workspace-icon workspace-folder-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.55" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
        <path d="M2.7 7.7V6.3c0-1 .8-1.8 1.8-1.8h5l2 2h8c1 0 1.8.8 1.8 1.8v2.1H4.5c-1 0-1.8.8-1.8 1.8v5.3" />
        <path d="M4.6 10.4h14.8a1.7 1.7 0 0 1 1.6 2.1l-1.5 6.1a1.8 1.8 0 0 1-1.7 1.4H5.4a1.8 1.8 0 0 1-1.7-1.4l-1.2-6.1a1.7 1.7 0 0 1 2.1-2.1Z" />
        {name === 'config' ? <>
          <circle cx="12" cy="15.3" r="2.1" strokeWidth="1.4" />
          <path d="M12 11.9v1.2m0 4.4v1.2m3.4-3.4h-1.2m-4.4 0H8.6" strokeWidth="1.2" />
        </> : <path d="m8 15.4 1.5 1.5 2.3-2.7m1.6 2.1h3.7" strokeWidth="1.45" />}
      </svg>
    );
  }
  if (name === 'new') {
    return <svg className="workspace-icon workspace-action-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true" focusable="false"><path d="M12 5v14M5 12h14" /></svg>;
  }
  if (name === 'upload') {
    return <svg className="workspace-icon workspace-action-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false"><path d="M12 15V4m-4 4 4-4 4 4M5 15v3.2c0 1 .8 1.8 1.8 1.8h10.4c1 0 1.8-.8 1.8-1.8V15" /></svg>;
  }
  return <svg className="workspace-icon workspace-chevron-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false"><path d="m9 5 7 7-7 7" /></svg>;
}
