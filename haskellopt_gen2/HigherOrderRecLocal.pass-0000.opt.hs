-- Generated Haskell code from Graph optimizer
-- Core obtained from: The Glorious Glasgow Haskell Compilation System, version 8.6.3
-- Optimized after GHC phase:
--   desugar
-- Beta reductions:  21
-- Incl. one-shot:  0
-- Case reductions:  0
-- Field reductions:  0
-- Total nodes: 703; Boxes: 234; Branches: 222
-- Apps: 114; Lams: 19

{-# LANGUAGE UnboxedTuples #-}
{-# LANGUAGE MagicHash #-}
{-# LANGUAGE NoMonomorphismRestriction  #-}

module HigherOrderRecLocal (foo_5,foo_4,foo_3,foo_1,foo_0,foo) where

import GHC.Base
import GHC.Num
import GHC.Types

foo_5 = \s -> let
  rec p'2 = 
        let rec'3 p'3 = p'3 : ((rec (p'3 + 1)) ++ (rec'3 (p'3 * 2))) in
        p'2 : ((rec (p'2 + 1)) ++ (rec'3 (p'2 * 2)))
  rec' p = 
        let rec'2 p' = p' : ((rec'2 (p' + 1)) ++ (rec' (p' * 2))) in
        p : ((rec'2 (p + 1)) ++ (rec' (p * 2)))
  in s : ((rec (s + 1)) ++ (rec' (s * 2)))

foo_4 = \s -> 
  let rec p = p : (rec (p + 1)) in
  s : (rec (s + 1))

foo_3 = \s -> 
  let rec s' = s' : (rec s') in
  s : (rec s)

foo_1 = id

foo_0 = 
  let _0 = id _0 in
  _0

foo = \f -> 
  let _0 = f _0 in
  _0