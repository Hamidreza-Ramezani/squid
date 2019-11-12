-- Generated Haskell code from Graph optimizer
-- Core obtained from: The Glorious Glasgow Haskell Compilation System, version 8.6.3
-- Optimized after GHC phase:
--   desugar
-- Beta reductions:  12
-- Incl. one-shot:   0
-- Case reductions:  0
-- Field reductions: 0
-- Case commutings:  0
-- Total nodes: 221; Boxes: 64; Branches: 46
-- Apps: 45; Lams: 12

{-# LANGUAGE UnboxedTuples #-}
{-# LANGUAGE MagicHash #-}
{-# LANGUAGE NoMonomorphismRestriction  #-}

module IterCont (nats1_5,nats1,loop1,nats0_5,nats0,loop0) where

import GHC.List
import GHC.Num
import GHC.Types

nats1_5 = 
  let rec st = st : (rec (st + (1::Int))) in
  GHC.List.take (5::Int) ((0::Int) : (rec ((0::Int) + (1::Int))))

nats1 = 
  let rec st = st : (rec (st + (1::Int))) in
  (0::Int) : (rec ((0::Int) + (1::Int)))

loop1 = \f -> \state -> 
        let λ = \st -> f λ st in
        f λ state

nats0_5 = let
  λ = \k -> \s -> s : k (s + (1::Int))
  rec f f' = f' (rec f f)
  _0 = (0::Int) + (1::Int)
  in GHC.List.take (5::Int) ((0::Int) : (_0 : (rec λ λ) (_0 + (1::Int))))

nats0 = let
  _0 = (0::Int) + (1::Int)
  λ = \k -> \s -> s : k (s + (1::Int))
  rec f f' = f' (rec f f)
  in (0::Int) : (_0 : (rec λ λ) (_0 + (1::Int)))

loop0 = \f -> 
  let rec f' f'2 = f'2 (rec f' f') in
  f (f (rec f f))
