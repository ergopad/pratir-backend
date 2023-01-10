package contracts

class BuyOrder {
    //R4: sale id
    //R5: pack id
    //R6: user address
    val script = """
    {
        sigmaProp(UserPK || PratirPK)
    }
    """
}
