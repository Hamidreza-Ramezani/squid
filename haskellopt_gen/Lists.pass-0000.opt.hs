-- Generated Haskell code from Graph optimizer
-- Core obtained from: The Glorious Glasgow Haskell Compilation System, version 8.6.3
-- Optimized after GHC phase:
--   desugar
-- Total nodes: 116; Boxes: 22; Branches: 4
-- Apps: 39; Lams: 5; Unreduced Redexes: 0

{-# LANGUAGE UnboxedTuples #-}
{-# LANGUAGE MagicHash #-}
{-# LANGUAGE NoMonomorphismRestriction  #-}

module Lists (lol,ls0,mutrec0,ls1,main,rec0,mutrec1) where

import Data.Foldable
import GHC.Base
import GHC.List
import GHC.Num
import GHC.Show
import GHC.Types
import System.IO

lol = (\x -> (\y -> (((GHC.Num.+) x) y)))

ls0 = ls0'

ls0' = (((:) 1) (((:) 2) (((:) 3) (((:) 4) []))))

ls1 = (GHC.Base.build (\c -> (\n -> (((GHC.Base.foldr (((GHC.Base..) c) (\ds -> (((GHC.Num.+) ds) (((GHC.Num.+) 11) 22))))) n) ls0'))))

main = (((GHC.Base.$) System.IO.print) (Data.Foldable.sum ls1))

mutrec0 = ((GHC.List.take (GHC.Types.I# 20#)) a)

a = (((:) 1) (((:) 2) a))

mutrec1 = ((GHC.List.take (GHC.Types.I# 30#)) _0)

_0 = (((:) (GHC.Num.fromInteger 1)) (((:) (GHC.Num.fromInteger 2)) _0))

rec0 = rec0'

rec0' = (((:) 1) rec0')
