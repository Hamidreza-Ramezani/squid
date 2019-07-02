module PatMat where


e0 = Just (2 :: Integer)
e0'0 = case e0 of { Just x -> x + 1; Nothing -> 0 }
e0'1 = case e0 of Just x -> x + 1 -- note: if not reduced, the default case has an ugly exception string
e0'2 = case e0 of { Just x -> x + 1; _ -> 0 }
e0'3 = case e0 of { Nothing -> 0; _ -> 1 }

e1 = Nothing :: Maybe Integer
e1'0 = case e1 of { Just x -> x + 1; Nothing -> 0 }
e1'1 = case e1 of { Just x -> x + 1; _ -> 0 }


f0 :: Maybe Integer -> Maybe Integer
f0 (Just x) = Just (x + 1)
-- f0 (Just x) = Just x
f0 Nothing = Just 0

f0'0 = f0 (Just 2)
f0'1 = f0 Nothing
f0'2 = f0 (f0 (Just 3))
f0'3 = f0 (f0 Nothing)

orZero :: Maybe Integer -> Integer
orZero (Just x) = x
orZero Nothing = 0

f1 :: Integer -> Maybe Integer
f1 x = if x > 0 then Just x else Nothing

-- TODO optimize across case branches
f1'0 = f1 4
f1'1 = case f1 5 of { Just x -> True; Nothing -> False }
f1'2 = orZero (f1 5)
-- f1'3 = f0 (f1 6)
