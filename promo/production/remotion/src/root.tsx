import React from 'react';
import {Composition} from 'remotion';
import {CialloPromo, TOTAL_FRAMES} from './video';

export const CialloRoot: React.FC = () => (
  <Composition
    id="CialloPromo"
    component={CialloPromo}
    durationInFrames={TOTAL_FRAMES}
    fps={30}
    width={1920}
    height={1080}
  />
);
